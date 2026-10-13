package com.eignex.klause.solver

import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.lp.BuiltInRelaxationProjectionProvider
import com.eignex.klause.lp.LinearLpProjection
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.propagation.FactorProjectionPreparation
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.SelectedProjectionChecks

/**
 * Immutable source-associated mechanism selection. Factories are retained, never mutable workspaces;
 * each engine opens its own projections. A source rewrite requires a fresh selection.
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
) {
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
            (propagation[id]?.builtIn != true || !usesBuiltInRelaxation(id))
    }

    /** Retain selection only for the identical canonical source; rewrites select against their own factors. */
    fun forProblem(problem: Problem): PreparedFactorProjections =
        if (this.problem === problem) this else providers.prepare(problem)

    internal fun forEngineView(view: Problem): PreparedFactorProjections = if (
        view.numBoolVars == problem.numBoolVars && view.numIntVars == problem.numIntVars &&
        view.numRealVars == problem.numRealVars && view.numFactors == problem.numFactors &&
        view.factors.indices.all { view.factors[it] === problem.factors[it] }
    ) projected(view, IntArray(view.numFactors) { it }) else forProblem(view)

    internal fun projected(view: Problem, factorIndices: IntArray): PreparedFactorProjections {
        require(factorIndices.size == view.numFactors && factorIndices.all { it in problem.factors.indices }) {
            "projection factor mapping differs from its prepared source"
        }
        return PreparedFactorProjections(view, providers,
            Array(factorIndices.size) { propagation[factorIndices[it]] },
            Array(factorIndices.size) { scoring[factorIndices[it]] },
            Array(factorIndices.size) { relaxation[factorIndices[it]] })
    }

    internal fun appended(view: Problem): PreparedFactorProjections {
        require(view.numFactors == problem.numFactors + 1)
        val extra = view.factors.last()
        return PreparedFactorProjections(view, providers,
            propagation + providers.propagation.select(extra),
            scoring + providers.scoring.select(extra),
            relaxation + providers.relaxation.select(extra))
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
            })
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
