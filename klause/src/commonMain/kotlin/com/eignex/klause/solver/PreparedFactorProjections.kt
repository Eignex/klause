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
import com.eignex.klause.lp.relaxation.LpPreparationTemplates
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.FactorProjectionPreparation
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.SelectedProjectionChecks
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.solver.objective.LinearObjective
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
    private val lpTemplateCache = AtomicReference<LpPreparationTemplates?>(null)

    internal fun prepareLpTemplates(
        objective: LinearObjective?, token: Cancellation, onWork: (Long) -> Unit = {},
    ): LpPreparationTemplates? {
        val previous = lpTemplateCache.load()
        val next = LpPreparationTemplates.prepare(this, lpTemplateNamespace, objective, previous, token, onWork)
            ?: return null
        lpTemplateCache.compareAndSet(previous, next)
        return next
    }

    private fun inheritLpTemplates(source: PreparedFactorProjections): PreparedFactorProjections = apply {
        lpTemplateNamespace = source.lpTemplateNamespace
        lpTemplateCache.store(source.lpTemplateCache.load())
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
    ) projected(view, IntArray(view.numFactors) { it }) else forProblem(view).inheritLpTemplates(this)

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
