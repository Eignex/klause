@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RetainedFactors
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.lp.BuiltInRelaxationProjectionProvider
import com.eignex.klause.lp.LinearLpProjection
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.relaxation.LpEmission
import com.eignex.klause.lp.relaxation.LpEmissionDemand
import com.eignex.klause.lp.relaxation.LpEmissionScope
import com.eignex.klause.lp.relaxation.LpEmissionTemplates
import com.eignex.klause.lp.relaxation.LpObservedMetadata
import com.eignex.klause.lp.relaxation.LpPreparationReuse
import com.eignex.klause.lp.relaxation.LpPreparationTemplates
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.FactorProjectionPreparation
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.SelectedProjectionChecks
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Immutable source-associated mechanism selection. Factories are retained, never mutable workspaces;
 * each engine opens its own projections. Rewrites require a new binding; checked source deletions
 * can retain descriptors through their explicit factor mapping.
 */
class PreparedFactorProjections internal constructor(
    /** Canonical model whose factor indices these selections describe. */
    val problem: Problem,
    /** Selectors retained for a later source rewrite. */
    val providers: FactorProjectionProviders,
    private val propagation: Array<PropagationProjection?> =
        Array(problem.numFactors) { providers.propagation.select(problem.factors[it]) },
    private val scoring: Array<ScoringProjection?> =
        Array(problem.numFactors) { providers.scoring.select(problem.factors[it]) },
    private val relaxation: Array<RelaxationProjection?> =
        Array(problem.numFactors) { providers.relaxation.select(problem.factors[it]) },
    private val encoders: Map<EncodingTarget, Array<Encoder>> = providers.encodingTargets.associateWith { target ->
        Array(problem.numFactors) { providers.encoder.select(problem.factors[it], target) }
    },
) {
    private var lpTemplateNamespace: Any = Any()
    private var lpTemplateRows: Any = Any()
    private var lpSourceTemplateCache = AtomicReference<LpPreparationReuse?>(null)
    private var lpFiniteTemplateCache = AtomicReference<LpPreparationReuse?>(null)

    private val lpTemplateCache: AtomicReference<LpPreparationReuse?>
        get() = if (problem is BakedProblem) lpFiniteTemplateCache else lpSourceTemplateCache

    internal val lpEmissionNamespace: Any get() = lpTemplateNamespace

    internal val lpEmissionTemplateUnits: Long
        get() = (lpTemplateCache.load() as? LpEmissionTemplates)?.storageUnits ?: 0L

    internal val lpPreparationReuseUnits: Long
        get() = when (val current = lpTemplateCache.load()) {
            is LpPreparationTemplates -> current.storageUnits
            is LpObservedMetadata -> current.metadata.storageUnits + LpObservedMetadata.STORAGE_UNITS
            is LpEmissionDemand -> LpObservedMetadata.STORAGE_UNITS
            is LpEmissionTemplates -> current.storageUnits
            null -> 0L
        }

    internal fun prepareLpTemplates(
        objective: LinearObjective?, token: Cancellation, reuseIndexes: Boolean = true,
        reuseMetadata: Boolean = false,
        onWork: (Long) -> Unit = {},
    ): LpPreparationTemplates? {
        val cache = lpTemplateCache
        val previous = cache.load()
        if (previous is LpEmissionTemplates) {
            token.charge(1L)
            onWork(1L)
            return null
        }
        val observed = previous as? LpObservedMetadata
        val next = LpPreparationTemplates.prepare(this, lpTemplateNamespace, lpTemplateRows, objective,
            observed?.metadata ?: previous as? LpPreparationTemplates, token, onWork,
            reuseIndexes, reuseMetadata)
            ?: return null
        val retained: LpPreparationReuse = if (observed != null &&
            observed.demand.scope.matches(lpTemplateNamespace, problem) &&
            next.storageUnits <= LpEmissionTemplates.MAX_UNITS - LpObservedMetadata.STORAGE_UNITS
        ) LpObservedMetadata(next, observed.demand) else next
        cache.compareAndSet(previous, retained)
        return next
    }

    internal fun borrowLpEmission(
        factorId: Int,
        objective: LinearObjective?,
        domains: RelaxationDomains,
        token: Cancellation,
        onWork: (Long) -> Unit,
    ): LpEmission? = (lpTemplateCache.load() as? LpEmissionTemplates)?.find(
        this, factorId, objective, domains, token, onWork,
    )

    internal fun rememberLpEmission(
        emission: LpEmission,
        attempt: Any,
        token: Cancellation,
        onWork: (Long) -> Unit,
    ): LpEmission? {
        val cache = lpTemplateCache
        val previous = cache.load()
        val metadata = (previous as? LpObservedMetadata)?.metadata ?: previous as? LpPreparationTemplates
        if (metadata != null && (metadata.objectiveIndependent ||
                metadata.storageUnits > LpEmissionTemplates.MAX_UNITS - LpObservedMetadata.STORAGE_UNITS)
        ) return null
        var work = 0L
        val units = try {
            LpEmissionTemplates.extent(problem, emission) { count ->
                work += count
                token.charge(count)
                !token()
            }
        } finally {
            onWork(work)
        }
        if (units == null || token()) return null
        if (previous is LpEmissionTemplates && previous.scope.matches(lpTemplateNamespace, problem)) {
            return previous.offer(problem, emission, units, token, onWork)
        }
        val demand = ((previous as? LpObservedMetadata)?.demand ?: previous as? LpEmissionDemand)
            ?.takeIf { it.scope.matches(lpTemplateNamespace, problem) }
        val capacity = LpEmissionTemplates.capacity(problem)
        return if (demand != null && demand.attempt !== attempt &&
            demand.paidUnits >= LpEmissionTemplates.indexUnits(capacity) * 4L
        ) {
            val prepared = LpEmissionTemplates.create(demand.scope, capacity, token, onWork) ?: return null
            if (cache.compareAndSet(previous, prepared)) prepared.offer(problem, emission, units, token, onWork)
            else null
        } else {
            val scope = demand?.scope ?: LpEmissionScope(lpTemplateNamespace, problem)
            val paid = ((demand?.paidUnits ?: 0L) + units)
                .coerceAtMost(LpEmissionTemplates.MAX_UNITS)
            val next = LpEmissionDemand(scope, attempt, paid)
            cache.compareAndSet(previous, if (metadata == null) next else LpObservedMetadata(metadata, next))
            null
        }
    }

    private fun inheritLpTemplates(source: PreparedFactorProjections): PreparedFactorProjections = apply {
        lpTemplateNamespace = source.lpTemplateNamespace
        lpSourceTemplateCache = source.lpSourceTemplateCache
        lpFiniteTemplateCache = source.lpFiniteTemplateCache
    }

    /** Immutable whole-relation selection in this canonical source's factor namespace. */
    fun encoder(factorId: Int, target: EncodingTarget): Encoder = encoders.getValue(target)[factorId]

    internal fun capturedEncoder(factorId: Int, target: EncodingTarget): Encoder? =
        encoders[target]?.getOrNull(factorId)

    internal fun requestEncoders(
        target: EncodingTarget,
        indices: List<Int>,
        fixedIntegers: Map<SourceIntId, BigInt> = emptyMap(),
        fixedBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
        cancellation: Cancellation = Cancellation.Never,
        completeFiniteConditions: Boolean = false,
        onDecline: (EncoderDecline) -> Unit = {},
    ): SelectedEncoderRequest? {
        require(target in encoders) { "consumer target was not captured for this source" }
        return SelectedEncoderRequest.prepare(this, target, indices, fixedIntegers, fixedBooleans,
            cancellation, completeFiniteConditions, onDecline)
    }

    private val declarations = Array(problem.numFactors) { id ->
        FactorExecutionCapabilities(
            propagation[id]?.capability ?: PropagationCapability.UNSUPPORTED,
            propagation[id]?.assignmentCheck ?: AssignmentCheckCapability.UNSUPPORTED,
            scoring[id]?.assignmentCheck ?: AssignmentCheckCapability.UNSUPPORTED,
            scoring[id]?.capability ?: ScoringCapability.UNSUPPORTED,
            relaxation[id]?.capability ?: RelaxationCapability.NONE,
            propagation[id]?.inertReason ?: scoring[id]?.inertReason,
        )
    }

    internal val nativePropagation: Boolean = propagation.all { it?.builtIn == true }
    internal val builtInScoring: Boolean = scoring.all { it?.builtIn == true }
    internal val builtInRelaxation: Boolean = providers.relaxation === BuiltInRelaxationProjectionProvider ||
        relaxation.indices.all(::usesBuiltInRelaxation)

    internal val originalChecks: SelectedProjectionChecks? = if (problem.factors.indices.any { id ->
            requiresOriginalCheck(id) && problem.factors[id].variables.reals.isEmpty() &&
                problem.factors[id] !is ObjectiveBoundFactor
        }) SelectedProjectionChecks(this) else null

    internal val requiresRealSourceCheck: Boolean = problem.factors.indices.any { id ->
        problem.factors[id].variables.reals.isNotEmpty() &&
            (problem.factors[id] is Distinct || problem.factors[id] is RealAbs ||
                problem.factors[id] is ContinuousProduct ||
                problem.factors[id] is RealFiniteDomain ||
                problem.factors[id] is ExactLinear || problem.factors[id] is LinearIndicator ||
                propagation[id]?.builtIn != true || !usesBuiltInRelaxation(id))
    }

    /** Retain selection only for the identical canonical source; rewrites select against their own factors. */
    fun forProblem(problem: Problem): PreparedFactorProjections =
        if (this.problem === problem) this else providers.prepare(problem)

    internal fun forEngineView(view: Problem): PreparedFactorProjections = if (view === problem) this else if (
        view.numBoolVars == problem.numBoolVars && view.numIntVars == problem.numIntVars &&
        view.numRealVars == problem.numRealVars && view.numFactors == problem.numFactors &&
        view.factors.indices.all { view.factors[it] === problem.factors[it] }
    ) projected(view, IntArray(view.numFactors) { it }).also {
        it.lpTemplateRows = lpTemplateRows
    } else forProblem(view).inheritLpTemplates(this)

    internal fun projected(view: Problem, factorIndices: IntArray, remap: VarRemap? = null): PreparedFactorProjections {
        require(factorIndices.size == view.numFactors && factorIndices.all { it in problem.factors.indices }) {
            "projection factor mapping differs from its prepared source"
        }
        return PreparedFactorProjections(view, providers,
            Array(factorIndices.size) { propagation[factorIndices[it]] },
            Array(factorIndices.size) { scoring[factorIndices[it]] },
            Array(factorIndices.size) { relaxation[factorIndices[it]] },
            encoders.mapValues { (_, selected) -> Array(factorIndices.size) {
                val encoder = selected[factorIndices[it]]
                if (remap == null) encoder else encoder.remapped(remap)
            } }).also { if (remap == null) it.inheritLpTemplates(this) }
    }

    internal fun retained(mapping: RetainedFactors): PreparedFactorProjections {
        requireSource(mapping.source)
        return projected(mapping.target, mapping.sourceIndices())
    }

    internal fun withAppendedFactors(view: Problem): PreparedFactorProjections {
        require(view.numFactors >= problem.numFactors &&
            problem.factors.indices.all { view.factors[it] === problem.factors[it] }) {
            "augmented factors must retain their prepared source prefix"
        }
        return PreparedFactorProjections(view, providers,
            Array(view.numFactors) {
                if (it < problem.numFactors) propagation[it] else providers.propagation.select(view.factors[it])
            },
            Array(view.numFactors) {
                if (it < problem.numFactors) scoring[it] else providers.scoring.select(view.factors[it])
            },
            Array(view.numFactors) {
                if (it < problem.numFactors) relaxation[it] else providers.relaxation.select(view.factors[it])
            }, encoders.mapValues { (target, selected) -> Array(view.numFactors) {
                if (it < problem.numFactors) selected[it] else providers.encoder.select(view.factors[it], target)
            } })
    }

    internal fun appended(view: Problem): PreparedFactorProjections {
        require(view.numFactors == problem.numFactors + 1)
        val extra = view.factors.last()
        return PreparedFactorProjections(view, providers,
            propagation + providers.propagation.select(extra),
            scoring + providers.scoring.select(extra),
            relaxation + providers.relaxation.select(extra),
            encoders.mapValues { (target, selected) -> selected + providers.encoder.select(extra, target) })
    }

    internal fun requireSource(problem: Problem) {
        require(this.problem === problem) { "projection selection belongs to a different canonical source" }
    }

    internal fun capabilities(factorId: Int): FactorExecutionCapabilities = declarations[factorId]

    internal fun usesBuiltInPropagation(factorId: Int): Boolean = propagation[factorId]?.builtIn == true

    internal fun usesBuiltInScoring(factorId: Int): Boolean = scoring[factorId]?.builtIn == true

    internal fun replacedLast(view: Problem): PreparedFactorProjections {
        require(view.numFactors == problem.numFactors && view.numFactors > 0)
        val last = view.numFactors - 1
        return PreparedFactorProjections(view, providers,
            Array(view.numFactors) {
                if (it == last) providers.propagation.select(view.factors[it]) else propagation[it]
            },
            Array(view.numFactors) {
                if (it == last) providers.scoring.select(view.factors[it]) else scoring[it]
            },
            Array(view.numFactors) {
                if (it == last) providers.relaxation.select(view.factors[it]) else relaxation[it]
            }, encoders.mapValues { (target, selected) -> Array(view.numFactors) {
                if (it == last) providers.encoder.select(view.factors[it], target) else selected[it]
            } })
    }

    internal fun requiresOriginalScoringCheck(factorId: Int): Boolean = scoring[factorId]?.builtIn == false &&
        scoring[factorId]?.assignmentCheck == AssignmentCheckCapability.SOURCE_CERTIFICATION &&
        problem.factors[factorId].variables.reals.isEmpty() && problem.factors[factorId] !is ObjectiveBoundFactor

    internal fun requiresOriginalCheck(factorId: Int): Boolean = propagation[factorId]?.builtIn == false &&
        propagation[factorId]?.assignmentCheck != AssignmentCheckCapability.EXACT

    internal fun propagator(factorId: Int, preparation: FactorProjectionPreparation?): Propagator =
        checkNotNull(propagation[factorId]) { "unsupported propagation route for factor $factorId" }
            .create(problem.factors[factorId], preparation)

    internal fun invariant(factorId: Int, domains: Array<IntDomain>?): Invariant =
        checkNotNull(scoring[factorId]) { "unsupported local-search route for factor $factorId" }
            .create(problem.factors[factorId], domains)

    internal fun usesBuiltInRelaxation(factorId: Int): Boolean = relaxation[factorId]?.builtIn == true ||
        (relaxation[factorId] == null &&
            problem.factors[factorId].defaultExecutionCapabilities().relaxation == RelaxationCapability.NONE)

    internal fun emitRelaxation(factorId: Int, builder: RelaxationBuilder, linear: LinearLpProjection?) {
        relaxation[factorId]?.emit(problem.factors[factorId], builder, linear)
    }
}

internal fun Problem.selectedFactorProjections(): PreparedFactorProjections =
    (this as? BakedProblem)?.factorProjections ?: FactorProjectionProviders.Default.prepare(this)
