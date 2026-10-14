package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.CpIntId
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.supportsCompleteDifferenceTheory
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.linearTheoryModel
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

/** Finite/linear projection mechanism retained by a [PreparedSourcePlan]. */
class ComponentPlan internal constructor(
    private val source: Problem,
    private val intOwners: Array<IntVariableOwner>,
    private val factorOwners: Array<FactorOwner>,
    /** Theory route selected from its participating source relations. */
    val theoryPipeline: ProblemPipeline,
    /** What left the model without a route, or null when it has one. */
    val unplaceable: UnplaceableColumn? = null,
    internal val factorProjections: PreparedFactorProjections = (source as? BakedProblem)?.factorProjections
        ?: FactorProjectionProviders.Default.prepare(source),
    private val encodingFactors: BooleanArray = BooleanArray(factorOwners.size) {
        factorOwners[it] == FactorOwner.THEORY
    },
) {
    init {
        factorProjections.requireSource(source)
    }

    /** Owner of integer column [v]. */
    fun intOwner(v: Int): IntVariableOwner = intOwners[v]

    /** Primary enforcing owner of source factor [i]; encoding participation is separate. */
    fun factorOwner(i: Int): FactorOwner = factorOwners[i]

    /** Whether arithmetic installs this factor alongside its primary enforcing owner. */
    fun factorEncoded(i: Int): Boolean = encodingFactors[i]

    internal val encodedFactorIndices: List<Int> get() = factorOwners.indices.filter { encodingFactors[it] }

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
        intOwners.any { it == IntVariableOwner.THEORY } || encodingFactors.any { it }

    /** Verify that this plan can hand the whole canonical model to finite preparation. */
    internal fun requireFullFiniteProjection(spec: Problem) {
        require(!hasTheoryComponent && cpIntVars.size == spec.numIntVars) {
            "a full finite projection requires every integer column to be CP-owned"
        }
        requireBelongsTo(spec)
    }

    /**
     * This plan for [spec], the model it was selected from without the factor a caller appended to it.
     *
     * Column ownership and every remaining factor's owner are the ones already selected, so the finite
     * projection is untouched and the theory fragment only loses a row — and a complete theory that
     * decides a fragment decides a subset of it. A caller whose own row belongs to some rounds and not
     * others selects ownership once from the shape carrying it, then drops it from plan and model
     * together.
     *
     * Retained factors must be the selected source factors by identity, and declarations may only
     * narrow their bounds. The resulting plan is bound to [spec].
     */
    internal fun withoutAppendedFactor(spec: Problem): ComponentPlan {
        requireSamePrefix(spec, factorOwners.size - 1)
        require(spec.factors.size == factorOwners.size - 1) {
            "dropping an appended factor needs the model the plan was selected from, minus that factor"
        }
        return ComponentPlan(
            spec,
            intOwners,
            factorOwners.copyOfRange(0, factorOwners.size - 1),
            theoryPipeline,
            unplaceable,
            factorProjections.projected(spec, IntArray(spec.numFactors) { it }),
            encodingFactors.copyOfRange(0, spec.numFactors),
        )
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
        val mapping = VarRemap(boolMap, sourceToCp)
        val factorIds = factorOwners.indices.filter { factorOwners[it] == FactorOwner.CP }
        val factors = factorIds.map { factor ->
            val sourceFactor = spec.factors[factor]
            require(sourceFactor.intVars.all { sourceToCp[it] >= 0 }) {
                "CP factor $factor mentions a theory-owned integer column"
            }
            sourceFactor.remap(mapping)
        }
            .toTypedArray()
        return CpProblemProjection(
            BakedProblem(
                settings = spec.settings,
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
                projectionProviders = factorProjections.providers,
                projectionSelection = { view -> factorProjections.projected(view, factorIds.toIntArray(), mapping) },
            ),
            sourceToCp,
            cpToSource,
        )
    }

    /**
     * Each CP-owned column's finite domain, materialized from what [spec] declares for it.
     *
     * The bound hull is the wrong reading: a column that admits less than its range would be searched
     * over values the model excludes, and the search would answer with one of them. Call this after any
     * open-bound closure, so a side a bound proved is the side the domain gets.
     */
    fun cpSourceDomains(spec: Problem): Map<Int, IntDomain> {
        requireBelongsTo(spec)
        return cpIntVars.associateWith { column -> spec.declaredIntDomains.finiteDomain(column) }
    }

    /** Source view consumed by the selected theory component. */
    fun theoryFragment(spec: Problem): Problem {
        requireBelongsTo(spec)
        val kept = selectedTheoryFactorIndices()
        return spec.withFactors(Array(kept.size) { spec.factors[kept[it]] },
            spec.impliedFactorMask?.let { mask -> BooleanArray(kept.size) { mask[kept[it]] } })
    }

    private fun selectedTheoryFactorIndices(): List<Int> = factorOwners.indices.filter {
        encodingFactors[it] || (theoryPipeline != ProblemPipeline.FINITE_CP && factorOwners[it] == FactorOwner.SHARED)
    }

    private val encodedFragment: Problem? by lazy {
        val target = if (theoryPipeline == ProblemPipeline.DIFFERENCE_THEORY) EncodingTarget.DIFFERENCE
        else EncodingTarget.LINEAR
        factorProjections.linearTheoryModel(target, selectedTheoryFactorIndices())
    }

    internal fun encodedTheoryFragment(spec: Problem): Problem? {
        requireBelongsTo(spec)
        return encodedFragment
    }

    // Shared clauses stay out because the shared clause component enforces them in the same session.
    internal fun theoryOwnedFragment(spec: Problem): Problem {
        requireBelongsTo(spec)
        val kept = encodedFactorIndices
        return spec.withFactors(Array(kept.size) { spec.factors[kept[it]] },
            spec.impliedFactorMask?.let { mask -> BooleanArray(kept.size) { mask[kept[it]] } })
    }

    internal fun forObjectiveRound(spec: Problem, appended: Boolean): ComponentPlan {
        val prefix = factorOwners.size - if (appended) 1 else 0
        requireSamePrefix(spec, prefix)
        require(spec.factors.size == factorOwners.size) { "objective round changed the factor count" }
        if (appended) {
            val selected = source.factors.last()
            val replacement = spec.factors.last()
            require(selected is Linear &&
                replacement is Linear &&
                selected.op == replacement.op && selected.vars.contentEquals(replacement.vars) &&
                selected.constants is IntegralConstants &&
                replacement.constants is IntegralConstants &&
                selected.vars.indices.all { k ->
                    selected.constants.exactCoeff(k) == replacement.constants.exactCoeff(k)
                }
            ) { "objective round must retain the planned row's columns and coefficients" }
        }
        return ComponentPlan(spec, intOwners, factorOwners, theoryPipeline, unplaceable,
            if (appended) factorProjections.replacedLast(spec)
            else factorProjections.projected(spec, IntArray(spec.numFactors) { it }), encodingFactors)
    }

    private fun requireSamePrefix(spec: Problem, count: Int) {
        require(spec.numBoolVars == source.numBoolVars && spec.numIntVars == source.numIntVars &&
            spec.numRealVars == source.numRealVars && spec.realLower.contentEquals(source.realLower) &&
            spec.realUpper.contentEquals(source.realUpper) && spec.factors.size >= count &&
            (0 until count).all { spec.factors[it] === source.factors[it] }
        ) { "component plan belongs to a different source model" }
        for (v in 0 until source.numIntVars) {
            val before = source.intBounds
            val after = spec.intBounds
            require(!before.hasLower(v) || (after.hasLower(v) && after.lower(v) >= before.lower(v)))
            require(!before.hasUpper(v) || (after.hasUpper(v) && after.upper(v) <= before.upper(v)))
            val declared = source.intDomainOrNull(v)
            val narrowed = spec.intDomainOrNull(v)
            require(declared != null || narrowed == null || narrowed.holeCount == 0L) {
                "objective round must only narrow declared values"
            }
            require(
                declared == null ||
                    (
                        narrowed != null && narrowed.min >= declared.min && narrowed.max <= declared.max &&
                            narrowed == declared.withMinAtLeast(narrowed.min).withMaxAtMost(narrowed.max)
                    ),
            ) {
                "objective round must only narrow declared values"
            }
        }
    }

    internal fun forLinearConditions(spec: Problem): ComponentPlan? {
        requireBelongsTo(spec)
        if (unplaceable != null) return null
        if (spec.numIntVars == 0 && spec.numRealVars == 0) return this
        val selected = spec.componentPlan(factorProjections)
        if (selected.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN) return null
        val fragment = factorProjections.linearTheoryModel(EncodingTarget.LINEAR,
            selected.selectedTheoryFactorIndices()) ?: return null
        val route = when {
            fragment.supportsExactLra() -> ProblemPipeline.EXACT_LRA
            fragment.supportsExactLira() -> ProblemPipeline.EXACT_LIRA
            else -> return null
        }
        return ComponentPlan(spec, intOwners, selected.factorOwners, route, factorProjections = factorProjections,
            encodingFactors = selected.encodingFactors)
    }

    internal fun requireSource(spec: Problem) = requireBelongsTo(spec)

    private fun requireBelongsTo(spec: Problem) {
        require(spec === source) {
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
    /** CP location for a source integer, or null when theory-owned. */
    fun cpId(sourceIntVar: SourceIntId): CpIntId? =
        cpId(sourceIntVar.index).takeIf { it >= 0 }?.let(::CpIntId)

    /** Source location for an integer owned by this CP projection. */
    fun sourceId(cpIntVar: CpIntId): SourceIntId = SourceIntId(sourceId(cpIntVar.index))

    /** CP-local id for [sourceIntVar], or `-1` when theory-owned. */
    fun cpId(sourceIntVar: Int): Int = sourceToCp[sourceIntVar]

    /** Source integer id for [cpIntVar]. */
    fun sourceId(cpIntVar: Int): Int = cpToSource[cpIntVar]
}

/** Select component ownership from the source model. */
fun Problem.componentPlan(preferFinite: Boolean = false): ComponentPlan = componentPlan(
    (this as? BakedProblem)?.factorProjections ?: FactorProjectionProviders.Default.prepare(this), preferFinite,
)

/** Select component ownership using the descriptors captured for this canonical source. */
fun Problem.componentPlan(projections: PreparedFactorProjections, preferFinite: Boolean = false): ComponentPlan {
    projections.requireSource(this)
    if (preferFinite) {
        return ComponentPlan(
            source = this,
            intOwners = Array(numIntVars) { IntVariableOwner.CP },
            factorOwners = Array(factors.size) { factor ->
                if (factors[factor] is Clause && projections.usesBuiltInPropagation(factor)) FactorOwner.SHARED
                else FactorOwner.CP
            },
            theoryPipeline = ProblemPipeline.FINITE_CP,
            factorProjections = projections,
        )
    }
    val intOwners = Array(numIntVars) { variable ->
        if (intBounds.hasLower(variable) && intBounds.hasUpper(variable)) IntVariableOwner.CP
        else IntVariableOwner.THEORY
    }
    val linear = projections.linearTheoryModel(EncodingTarget.LINEAR)
    val difference = projections.linearTheoryModel(EncodingTarget.DIFFERENCE)
    val completeTheory = when {
        linear?.supportsExactLra() == true -> ProblemPipeline.EXACT_LRA
        difference != null && numRealVars == 0 && supportsCompleteDifferenceTheory(
            difference.factors, numIntVars, intBounds) -> ProblemPipeline.DIFFERENCE_THEORY
        linear?.supportsExactLira() == true -> ProblemPipeline.EXACT_LIRA
        else -> null
    }
    val target = if (completeTheory == ProblemPipeline.DIFFERENCE_THEORY) EncodingTarget.DIFFERENCE
        else EncodingTarget.LINEAR
    val factorOwners = Array(factors.size) { index ->
        val factor = factors[index]
        when {
            factor is Clause && projections.usesBuiltInPropagation(index) -> FactorOwner.SHARED
            factor.variables.reals.isEmpty() && factor.intVars.all { intOwners[it] == IntVariableOwner.CP } &&
                projections.capabilities(index).propagation != PropagationCapability.UNSUPPORTED ->
                FactorOwner.CP
            projections.encoder(index, target).enforcesSource -> FactorOwner.THEORY
            else -> FactorOwner.CP
        }
    }
    val relevantIntegers = BooleanArray(numIntVars) { intOwners[it] == IntVariableOwner.THEORY }
    val relevantReals = BooleanArray(numRealVars) { true }
    val encoded = BooleanArray(factors.size) { factorOwners[it] == FactorOwner.THEORY }
    val jointFinite = numRealVars == 0 && relevantIntegers.none { it } && completeTheory != null
    if (jointFinite) relevantIntegers.fill(true)
    var changed = true
    while (changed) {
        changed = false
        for ((index, factor) in factors.withIndex()) {
            if (!projections.encoder(index, target).enforcesSource ||
                (!encoded[index] && factor.intVars.none { relevantIntegers[it] } &&
                    factor.variables.reals.none { relevantReals[it] })
            ) continue
            encoded[index] = true
            for (variable in factor.intVars) if (!relevantIntegers[variable]) {
                relevantIntegers[variable] = true
                changed = true
            }
            for (variable in factor.variables.reals) if (!relevantReals[variable]) {
                relevantReals[variable] = true
                changed = true
            }
        }
    }
    val unownedOpenColumn = factors.indices.firstNotNullOfOrNull { index ->
        if (factorOwners[index] != FactorOwner.CP) null
        else factors[index].intVars.firstOrNull { intOwners[it] == IntVariableOwner.THEORY }
    }
    val unownedRealFactor = factors.indices.any { index ->
        factorOwners[index] == FactorOwner.CP && factors[index].variables.reals.isNotEmpty()
    }
    val kept = factors.indices.filter { encoded[it] || factorOwners[it] == FactorOwner.SHARED }
    val fragment = projections.linearTheoryModel(EncodingTarget.LINEAR, kept)
    val differenceFragment = projections.linearTheoryModel(EncodingTarget.DIFFERENCE, kept)
    val needsArithmetic = intOwners.any { it == IntVariableOwner.THEORY } || encoded.any { it } || numRealVars != 0
    val route = when {
        unownedOpenColumn != null || unownedRealFactor -> ProblemPipeline.UNSUPPORTED_OPEN
        !needsArithmetic -> ProblemPipeline.FINITE_CP
        fragment?.supportsExactLra() == true -> ProblemPipeline.EXACT_LRA
        differenceFragment != null && numRealVars == 0 && supportsCompleteDifferenceTheory(
            differenceFragment.factors, numIntVars, intBounds) -> ProblemPipeline.DIFFERENCE_THEORY
        fragment?.supportsExactLira() == true -> ProblemPipeline.EXACT_LIRA
        else -> ProblemPipeline.UNSUPPORTED_OPEN
    }
    return ComponentPlan(this, intOwners, factorOwners, route,
        unownedOpenColumn?.let { unplaceable(it, projections, numRealVars != 0) }, projections, encoded)
}

/**
 * The column no lane could own, and the first factor that demanded it be finite.
 *
 * A refusal that names only the model leaves nothing to act on: the column has no bound for CP to index,
 * and some factor no theory holds reads it. Which factor that is decides the remedy — bound the column,
 * or state a decomposition for that global the theories can take.
 */
private fun unplaceable(
    column: Int,
    projections: PreparedFactorProjections,
    hasRealColumns: Boolean,
): UnplaceableColumn {
    val factors = projections.problem.factors
    val culprit = factors.indices.firstOrNull { index ->
        !projections.theoryOwnable(index, hasRealColumns) && factors[index].variables.ints.any { it == column }
    } ?: -1
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
