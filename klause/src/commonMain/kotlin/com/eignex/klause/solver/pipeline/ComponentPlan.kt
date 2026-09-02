package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.supportsCompleteDifferenceTheory
import com.eignex.klause.theory.qflra.supportsExactLira
import com.eignex.klause.theory.qflra.supportsExactLra

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

/** Immutable build-time decomposition of a [Problem]. */
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

    /** Verify that this plan can hand the whole canonical model to finite preparation. */
    internal fun requireFullFiniteProjection(spec: Problem) {
        require(!hasTheoryComponent && cpIntVars.size == spec.numIntVars) {
            "a full finite projection requires every integer column to be CP-owned"
        }
        requireBelongsTo(spec)
    }

    /** Build the compact finite problem owned by the CP component. */
    fun cpProjection(spec: Problem, cpDomains: Map<Int, IntDomain>): CpProblemProjection {
        requireBelongsTo(spec)
        val sourceToCp = IntArray(intOwners.size) { -1 }
        val cpToSource = cpIntVars
        for (local in cpToSource.indices) sourceToCp[cpToSource[local]] = local
        val domains = Array(cpToSource.size) { local ->
            requireNotNull(cpDomains[cpToSource[local]]) {
                "missing finite domain for CP integer column ${cpToSource[local]}"
            }
        }
        val boolMap = IntArray(spec.numBoolVars) { it }
        val factorIds = factorOwners.indices.filter { factorOwners[it] == FactorOwner.CP }
        val factors = factorIds.map { factor ->
            val sourceFactor = spec.factors[factor]
            require(sourceFactor.intVars.all { sourceToCp[it] >= 0 }) {
                "CP factor $factor mentions a theory-owned integer column"
            }
            sourceFactor.remap(VarRemap(boolMap, sourceToCp))
        }
            .toTypedArray()
        return CpProblemProjection(
            BakedProblem(
                numBoolVars = spec.numBoolVars,
                numIntVars = domains.size,
                intDomains = domains,
                factors = factors,
                impliedFactorMask = spec.impliedFactorMask?.let { mask ->
                    BooleanArray(factorIds.size) { mask[factorIds[it]] }
                },
                hasSymmetryBreaking = spec.hasSymmetryBreaking,
                numRealVars = 0,
                realLower = doubleArrayOf(),
                realUpper = doubleArrayOf(),
            ),
            sourceToCp,
            cpToSource,
        )
    }

    /** Source view consumed by the selected theory component. */
    fun theoryFragment(spec: Problem): Problem = fragment(spec) { it != FactorOwner.CP }

    // Shared clauses stay out because the shared clause component enforces them in the same session.
    internal fun theoryOwnedFragment(spec: Problem): Problem = fragment(spec) { it == FactorOwner.THEORY }

    private fun fragment(spec: Problem, keep: (FactorOwner) -> Boolean): Problem {
        requireBelongsTo(spec)
        val kept = factorOwners.indices.filter { keep(factorOwners[it]) }
        return spec.withFactors(
            Array(kept.size) { spec.factors[kept[it]] },
            spec.impliedFactorMask?.let { mask -> BooleanArray(kept.size) { mask[kept[it]] } },
        )
    }

    private fun requireBelongsTo(spec: Problem) {
        require(spec.numIntVars == intOwners.size && spec.factors.size == factorOwners.size) {
            "component plan belongs to a different source model"
        }
    }
}

/** Mapping between source integer ids and the compact finite CP component. */
class CpProblemProjection internal constructor(
    /** Finite-domain problem passed to the propagation component. */
    val problem: BakedProblem,
    private val sourceToCp: IntArray,
    private val cpToSource: IntArray,
) {
    /** CP-local id for [sourceIntVar], or `-1` when theory-owned. */
    fun cpId(sourceIntVar: Int): Int = sourceToCp[sourceIntVar]

    /** Source integer id for [cpIntVar]. */
    fun sourceId(cpIntVar: Int): Int = cpToSource[cpIntVar]
}

/** Select component ownership from the source model. */
fun Problem.componentPlan(preferFinite: Boolean = false): ComponentPlan {
    if (preferFinite) {
        return ComponentPlan(
            intOwners = Array(numIntVars) { IntVariableOwner.CP },
            factorOwners = Array(factors.size) { factor ->
                if (factors[factor] is Clause) FactorOwner.SHARED else FactorOwner.CP
            },
            theoryPipeline = ProblemPipeline.FINITE_CP,
        )
    }
    val partition = variablePartition()
    val completeTheory = when {
        supportsExactLra() -> ProblemPipeline.EXACT_LRA

        numRealVars == 0 && supportsCompleteDifferenceTheory(
            factors,
            numIntVars,
            intBounds,
        ) -> ProblemPipeline.DIFFERENCE_THEORY

        supportsExactLira() -> ProblemPipeline.EXACT_LIRA

        numRealVars != 0 -> null

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
            completeTheory == ProblemPipeline.EXACT_LIRA && factor.exactTheoryOwnable -> FactorOwner.THEORY
            factor.isTheoryOwnable(numRealVars != 0) -> FactorOwner.THEORY
            else -> FactorOwner.CP
        }
    }
    val theoryFragment = withFactors(
        factorOwners.indices.asSequence()
            .filter { factorOwners[it] != FactorOwner.CP }
            .map { factors[it] }
            .toList()
            .toTypedArray(),
    )
    val route = when {
        unownedOpenColumn >= 0 -> ProblemPipeline.UNSUPPORTED_OPEN

        theoryFragment.supportsExactLra() -> ProblemPipeline.EXACT_LRA

        intOwners.any { it == IntVariableOwner.THEORY } || factorOwners.any { it == FactorOwner.THEORY } -> when {
            theoryFragment.supportsExactLra() -> ProblemPipeline.EXACT_LRA

            theoryFragment.numRealVars == 0 && supportsCompleteDifferenceTheory(
                theoryFragment.factors,
                theoryFragment.numIntVars,
                theoryFragment.intBounds,
            ) -> ProblemPipeline.DIFFERENCE_THEORY

            theoryFragment.supportsExactLira() -> ProblemPipeline.EXACT_LIRA

            theoryFragment.numRealVars != 0 -> ProblemPipeline.UNSUPPORTED_OPEN

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
