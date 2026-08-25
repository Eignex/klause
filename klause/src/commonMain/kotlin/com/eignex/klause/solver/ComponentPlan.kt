package com.eignex.klause.solver

import com.eignex.klause.arithmetic.difference.supportsCompleteDifferenceTheory
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause

/** Ownership of an integer column selected once before a search begins. */
enum class IntVariableOwner {
    /** The finite CP component owns mutable domain state and may branch on this column. */
    CP,

    /** A theory component owns this symbolic column; it has no [IntDomain] in [Problem]. */
    THEORY,
}

/** Destination of a source factor in a composed search. */
enum class FactorOwner {
    /** A finite-domain propagator owns the factor. */
    CP,

    /** An open theory component owns the factor. */
    THEORY,

    /** The session's Boolean clause component owns the factor. */
    SHARED,
}

/**
 * Immutable build-time decomposition of a [ProblemSpec].
 *
 * The plan is deliberately source-indexed: components retain the model's public variable ids, while
 * each component allocates only the mutable state it owns. This avoids a second id mapping at every
 * theory/CP channel boundary and lets future array, function, and quantifier components retain their
 * native symbols.
 */
class ComponentPlan internal constructor(
    private val intOwners: Array<IntVariableOwner>,
    private val factorOwners: Array<FactorOwner>,
    /** Theory route selected from the theory-owned source fragment, once at build time. */
    val theoryPipeline: ProblemPipeline,
) {
    /** Owner of integer column [v]. */
    fun intOwner(v: Int): IntVariableOwner = intOwners[v]

    /** Owner of source factor [i]. */
    fun factorOwner(i: Int): FactorOwner = factorOwners[i]

    /** Source integer ids requiring finite CP domains. */
    val cpIntVars: IntArray get() = intOwners.indices.filter { intOwners[it] == IntVariableOwner.CP }.toIntArray()

    /** Source integer ids that remain symbolic for a theory. */
    val theoryIntVars: IntArray get() = intOwners.indices.filter {
        intOwners[it] == IntVariableOwner.THEORY
    }.toIntArray()

    /** Whether this composition has finite-domain state. */
    val hasCpComponent: Boolean get() = intOwners.any { it == IntVariableOwner.CP } ||
        factorOwners.any { it == FactorOwner.CP }

    /** Whether this composition needs a theory component. */
    val hasTheoryComponent: Boolean get() =
        intOwners.any { it == IntVariableOwner.THEORY } || factorOwners.any { it == FactorOwner.THEORY }

    /** Materialize exactly the CP-owned columns, leaving theory columns symbolic. */
    fun materialize(spec: ProblemSpec, cpDomains: Map<Int, IntDomain>): Problem {
        require(cpDomains.keys.all { it in intOwners.indices && intOwners[it] == IntVariableOwner.CP }) {
            "only CP-owned integer columns may receive finite domains"
        }
        if (!hasTheoryComponent) {
            val domains = Array(intOwners.size) { v ->
                requireNotNull(cpDomains[v]) { "missing finite domain for CP integer column $v" }
            }
            return spec.materialize(FiniteIntColumns(domains))
        }
        val columns = Array<IntColumn>(intOwners.size) { v ->
            when (intOwners[v]) {
                IntVariableOwner.CP -> IntColumn.Finite(
                    requireNotNull(cpDomains[v]) { "missing finite domain for CP integer column $v" },
                )

                // The theory reasons over the source bounds, so the column carries them rather than
                // standing for their absence and sending every reader to a parallel table.
                IntVariableOwner.THEORY -> IntColumn.Bounded(
                    lower = if (spec.intBounds.hasLower(v)) spec.intBounds.lower(v) else null,
                    upper = if (spec.intBounds.hasUpper(v)) spec.intBounds.upper(v) else null,
                )
            }
        }
        return spec.materialize(MixedIntColumns(columns))
    }

    /** Build the compact finite problem owned by the CP component. */
    fun cpProjection(spec: ProblemSpec, cpDomains: Map<Int, IntDomain>): CpProblemProjection {
        require(spec.numIntVars == intOwners.size && spec.factors.size == factorOwners.size) {
            "component plan belongs to a different source model"
        }
        val sourceToCp = IntArray(intOwners.size) { -1 }
        val cpToSource = cpIntVars
        for (local in cpToSource.indices) sourceToCp[cpToSource[local]] = local
        val domains = Array(cpToSource.size) { local ->
            requireNotNull(cpDomains[cpToSource[local]]) {
                "missing finite domain for CP integer column ${cpToSource[local]}"
            }
        }
        val boolMap = IntArray(spec.numBoolVars) { it }
        val factors = factorOwners.indices.asSequence()
            .filter { factorOwners[it] == FactorOwner.CP }
            .map { factor ->
                val sourceFactor = spec.factors[factor]
                require(sourceFactor.intVars.all { sourceToCp[it] >= 0 }) {
                    "CP factor $factor mentions a theory-owned integer column"
                }
                sourceFactor.remap(boolMap, sourceToCp)
            }
            .toList()
            .toTypedArray()
        return CpProblemProjection(
            Problem(spec.numBoolVars, domains.size, domains, factors),
            sourceToCp,
            cpToSource,
        )
    }

    /** Source view consumed by the selected theory component. */
    fun theoryFragment(spec: ProblemSpec): ProblemSpec {
        require(spec.numIntVars == intOwners.size && spec.factors.size == factorOwners.size) {
            "component plan belongs to a different source model"
        }
        return ProblemSpec(
            numBoolVars = spec.numBoolVars,
            intBounds = spec.intBounds,
            factors = factorOwners.indices.asSequence()
                .filter { factorOwners[it] != FactorOwner.CP }
                .map { spec.factors[it] }
                .toList()
                .toTypedArray(),
            seedDeductions = spec.seedDeductions,
            cancellation = spec.cancellation,
            numRealVars = spec.numRealVars,
            realLower = spec.realLower,
            realUpper = spec.realUpper,
        )
    }
}

/** Mapping between source integer ids and the compact finite CP component. */
class CpProblemProjection internal constructor(
    /** The finite-domain problem passed to [com.eignex.klause.propagation.PropagationSession]. */
    val problem: Problem,
    private val sourceToCp: IntArray,
    private val cpToSource: IntArray,
) {
    /** CP-local id for a source integer column, or `-1` when theory-owned. */
    fun cpId(sourceIntVar: Int): Int = sourceToCp[sourceIntVar]

    /** Source integer id for a CP-local column. */
    fun sourceId(cpIntVar: Int): Int = cpToSource[cpIntVar]
}

/**
 * Select component ownership from the source model before any finite search domain is invented.
 *
 * When one complete theory covers the entire open source model, every integer stays theory-owned,
 * including declared finite ranges: a finite bound is then a theory constraint, not a CP allocation.
 * Otherwise a variable that reaches a finite-domain global is CP-owned, while arithmetic stays in its
 * theory component and reads CP values only through session-published bounds. Clauses are session-owned
 * shared factors.
 */
fun ProblemSpec.componentPlan(): ComponentPlan {
    val partition = variablePartition()
    val completeTheory = when {
        supportsExactLra() -> ProblemPipeline.EXACT_LRA
        supportsExactLira() -> ProblemPipeline.EXACT_LIRA
        numRealVars != 0 -> null
        supportsCompleteDifferenceTheory(factors, numIntVars, intBounds) -> ProblemPipeline.DIFFERENCE_THEORY
        generalLiaWitnessBound() != null -> ProblemPipeline.GENERAL_LIA
        else -> null
    }
    val intOwners = Array(numIntVars) { v ->
        if (completeTheory != null) {
            IntVariableOwner.THEORY
        } else if (!intBounds.hasLower(v) || !intBounds.hasUpper(v)) {
            require(partition.isTheoryEligible(v)) {
                "open integer column $v reaches a finite-domain factor and cannot be theory-owned"
            }
            IntVariableOwner.THEORY
        } else if (columnMustBeCpOwned(v, numRealVars != 0)) {
            IntVariableOwner.CP
        } else {
            IntVariableOwner.THEORY
        }
    }
    val factorOwners = Array(factors.size) { i ->
        val factor = factors[i]
        when {
            factor is Clause -> FactorOwner.SHARED

            factor.supportsIntegerTheory() -> FactorOwner.THEORY

            numRealVars != 0 && supportsExactTheoryFactor(factor) -> FactorOwner.THEORY

            else -> {
                require(factor.intVars.all { intOwners[it] == IntVariableOwner.CP }) {
                    "finite-domain factor $i mentions a symbolic integer column"
                }
                FactorOwner.CP
            }
        }
    }
    val theoryFragment = ProblemSpec(
        numBoolVars = numBoolVars,
        intBounds = intBounds,
        factors = factorOwners.indices.asSequence()
            .filter { factorOwners[it] != FactorOwner.CP }
            .map { factors[it] }
            .toList()
            .toTypedArray(),
        seedDeductions = seedDeductions,
        cancellation = cancellation,
        numRealVars = numRealVars,
        realLower = realLower,
        realUpper = realUpper,
    )
    val route = when {
        theoryFragment.supportsExactLra() -> ProblemPipeline.EXACT_LRA

        intOwners.any { it == IntVariableOwner.THEORY } || factorOwners.any { it == FactorOwner.THEORY } -> when {
            theoryFragment.supportsExactLra() -> ProblemPipeline.EXACT_LRA

            theoryFragment.supportsExactLira() -> ProblemPipeline.EXACT_LIRA

            theoryFragment.numRealVars != 0 -> ProblemPipeline.UNSUPPORTED_OPEN

            supportsCompleteDifferenceTheory(
                theoryFragment.factors,
                theoryFragment.numIntVars,
                theoryFragment.intBounds,
            ) -> ProblemPipeline.DIFFERENCE_THEORY

            theoryFragment.generalLiaWitnessBound() != null -> ProblemPipeline.GENERAL_LIA

            else -> ProblemPipeline.UNSUPPORTED_OPEN
        }

        else -> ProblemPipeline.FINITE_CP
    }
    return ComponentPlan(
        intOwners,
        factorOwners,
        route,
    )
}

internal fun Factor.supportsIntegerTheory(): Boolean = this is Linear || this is ReifiedLinear ||
    this is ComparisonClause
