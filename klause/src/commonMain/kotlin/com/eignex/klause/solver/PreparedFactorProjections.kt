package com.eignex.klause.solver

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearLpProjection
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.BuiltInRelaxationProjectionProvider
import com.eignex.klause.propagation.BuiltInPropagationProjectionProvider
import com.eignex.klause.propagation.FactorProjectionPreparation
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.SelectedProjectionChecks
import com.eignex.klause.factor.objective.ObjectiveBoundFactor

/**
 * Immutable source-associated mechanism selection. Factories are retained, never mutable workspaces;
 * each engine opens its own projections. A source rewrite requires a fresh selection.
 */
class PreparedFactorProjections internal constructor(
    /** Canonical model whose factor indices these selections describe. */
    val problem: Problem,
    /** Selectors retained for a later source rewrite. */
    val providers: FactorProjectionProviders,
) {
    private val propagation = Array(problem.numFactors) { providers.propagation.select(problem.factors[it]) }
    private val scoring = Array(problem.numFactors) { providers.scoring.select(problem.factors[it]) }
    private val relaxation = Array(problem.numFactors) { providers.relaxation.select(problem.factors[it]) }
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

    internal val nativePropagation: Boolean = providers.propagation === BuiltInPropagationProjectionProvider
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

    internal fun requireSource(problem: Problem) {
        require(this.problem === problem) { "projection selection belongs to a different canonical source" }
    }

    internal fun capabilities(factorId: Int): FactorExecutionCapabilities = declarations[factorId]

    internal fun requiresOriginalCheck(factorId: Int): Boolean = propagation[factorId]?.builtIn == false &&
        propagation[factorId]?.assignmentCheck != AssignmentCheckCapability.EXACT


    internal fun propagator(factorId: Int, preparation: FactorProjectionPreparation?): Propagator =
        checkNotNull(propagation[factorId]) { "unsupported propagation route for factor $factorId" }.create(preparation)

    internal fun invariant(factorId: Int, domains: Array<IntDomain>?): Invariant =
        checkNotNull(scoring[factorId]) { "unsupported local-search route for factor $factorId" }.create(domains)

    internal fun usesBuiltInRelaxation(factorId: Int): Boolean = relaxation[factorId]?.builtIn == true ||
        (relaxation[factorId] == null &&
            problem.factors[factorId].defaultExecutionCapabilities().relaxation == RelaxationCapability.NONE)

    internal fun emitRelaxation(factorId: Int, builder: RelaxationBuilder, linear: LinearLpProjection?) {
        relaxation[factorId]?.emit(builder, linear)
    }
}
