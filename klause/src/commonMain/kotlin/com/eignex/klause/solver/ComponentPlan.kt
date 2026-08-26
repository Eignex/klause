package com.eignex.klause.solver

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.admitsGeneralLia
import com.eignex.klause.solver.pipeline.isTheoryOwnable
import com.eignex.klause.solver.pipeline.supportsExactLira
import com.eignex.klause.solver.pipeline.supportsExactLra

/** Ownership of an integer column selected once before a search begins. */
enum class IntVariableOwner {
    /** Finite-domain search owns the column. */
    CP,

    /** An open theory owns the column. */
    THEORY,
}

/** Destination of a source factor in a composed search. */
enum class FactorOwner {
    /** A finite-domain propagator owns the factor. */
    CP,

    /** An open theory owns the factor. */
    THEORY,

    /** The shared Boolean clause component owns the factor. */
    SHARED,
}

/** Immutable build-time decomposition of a [ProblemSpec]. */
class ComponentPlan internal constructor(
    private val intOwners: Array<IntVariableOwner>,
    private val factorOwners: Array<FactorOwner>,
    /** Theory route selected from the theory-owned source fragment. */
    val theoryPipeline: ProblemPipeline,
    /** What left the model without a route, or null when it has one. */
    val unplaceable: UnplaceableColumn? = null,
) {
    /** Owner of integer column [v]. */
    fun intOwner(v: Int): IntVariableOwner = intOwners[v]

    /** Owner of source factor [i]. */
    fun factorOwner(i: Int): FactorOwner = factorOwners[i]

    /** Source integer ids requiring finite domains. */
    val cpIntVars: IntArray get() = intOwners.indices.filter { intOwners[it] == IntVariableOwner.CP }.toIntArray()

    /** Source integer ids that remain symbolic for a theory. */
    val theoryIntVars: IntArray get() = intOwners.indices.filter {
        intOwners[it] == IntVariableOwner.THEORY
    }.toIntArray()

    /** Whether the composition has finite-domain state. */
    val hasCpComponent: Boolean get() = intOwners.any { it == IntVariableOwner.CP } ||
        factorOwners.any { it == FactorOwner.CP }

    /** Whether the composition needs a theory component. */
    val hasTheoryComponent: Boolean get() =
        intOwners.any { it == IntVariableOwner.THEORY } || factorOwners.any { it == FactorOwner.THEORY }

    /** Materialize the finite-domain columns of [spec]. */
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
                sourceFactor.remap(VarRemap(boolMap, sourceToCp))
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
    /** Finite-domain problem passed to the propagation component. */
    val problem: Problem,
    private val sourceToCp: IntArray,
    private val cpToSource: IntArray,
) {
    /** CP-local id for [sourceIntVar], or `-1` when theory-owned. */
    fun cpId(sourceIntVar: Int): Int = sourceToCp[sourceIntVar]

    /** Source integer id for [cpIntVar]. */
    fun sourceId(cpIntVar: Int): Int = cpToSource[cpIntVar]
}

/** Select component ownership from the source model. */
fun ProblemSpec.componentPlan(): ComponentPlan {
    val partition = variablePartition()
    val completeTheory = when {
        supportsExactLra() -> ProblemPipeline.EXACT_LRA
        supportsExactLira() -> ProblemPipeline.EXACT_LIRA
        numRealVars != 0 -> null
        supportsCompleteDifferenceTheory(factors, numIntVars, intBounds) -> ProblemPipeline.DIFFERENCE_THEORY
        admitsGeneralLia() -> ProblemPipeline.GENERAL_LIA
        else -> null
    }
    // An open column some CP-only factor reads has no owner: CP cannot index it and no theory can hold
    // the factor. The plan says so rather than asserting it away, so one model has one verdict however
    // it was reached.
    var unownedOpenColumn = -1
    val intOwners = Array(numIntVars) { v ->
        if (completeTheory != null) {
            IntVariableOwner.THEORY
        } else if (!intBounds.hasLower(v) || !intBounds.hasUpper(v)) {
            if (partition.isSearchVariable(v) && unownedOpenColumn < 0) unownedOpenColumn = v
            IntVariableOwner.THEORY
        } else if (partition.isSearchVariable(v)) {
            IntVariableOwner.CP
        } else {
            IntVariableOwner.THEORY
        }
    }
    val factorOwners = Array(factors.size) { i ->
        val factor = factors[i]
        when {
            factor is Clause -> FactorOwner.SHARED
            factor.isTheoryOwnable(numRealVars != 0) -> FactorOwner.THEORY
            else -> FactorOwner.CP
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
        unownedOpenColumn >= 0 -> ProblemPipeline.UNSUPPORTED_OPEN

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

            theoryFragment.admitsGeneralLia() -> ProblemPipeline.GENERAL_LIA

            else -> ProblemPipeline.UNSUPPORTED_OPEN
        }

        else -> ProblemPipeline.FINITE_CP
    }
    return ComponentPlan(
        intOwners,
        factorOwners,
        route,
        // Only searched on the refusal path, where the model is already declined and the scan is the
        // difference between naming the constraint at fault and naming nothing.
        if (unownedOpenColumn < 0) null else unplaceable(unownedOpenColumn, factors, numRealVars != 0),
    )
}

/**
 * The column no lane could own, and the first factor that demanded it be finite.
 *
 * A refusal that names only the model leaves nothing to act on: the column has no bound for CP to index,
 * and some factor no theory holds reads it. Which factor that is decides the remedy — bound the column,
 * or state a decomposition for that global the theories can take.
 */
private fun unplaceable(column: Int, factors: Array<Factor>, hasRealColumns: Boolean): UnplaceableColumn {
    val culprit = factors.indexOfFirst { f ->
        !f.isTheoryOwnable(hasRealColumns) && f.variables.ints.any { it == column }
    }
    return UnplaceableColumn(
        column,
        culprit.takeIf { it >= 0 },
        factors.getOrNull(culprit)?.let { it::class.simpleName },
    )
}

/** Why a model has no route; see [ComponentPlan.unplaceable]. */
class UnplaceableColumn(
    /** Integer column with no finite bound that a domain-requiring factor reads. */
    val column: Int,
    /** Index of the factor demanding it, or null when none was found. */
    val factorIndex: Int?,
    /** That factor's type name, for a diagnostic that names the constraint. */
    val factorKind: String?,
)
