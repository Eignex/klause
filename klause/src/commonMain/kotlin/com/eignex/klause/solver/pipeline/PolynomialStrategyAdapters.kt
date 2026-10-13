package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation

/** Finite strategy controls for polynomial satisfaction and finite-objective optimization. */
data class PolynomialStrategyParams(
    /** Complete finite traversal parameters; theory components are supplied by the pipeline. */
    val backtrack: BacktrackParams = BacktrackParams(),
    /** Finite heuristic walk parameters. Continuous completion is owned by the pipeline. */
    val localSearch: LocalSearchParams = LocalSearchParams(),
    /** Common finite pins captured for this invocation. */
    val assumptions: Assumptions = Assumptions.None,
    /** ALNS destroy/repair iteration cap. */
    val alnsIterations: Int = 50,
) {
    init { require(alnsIterations >= 0) }
}

internal class PolynomialStrategyAdapters(
    private val plan: PreparedSourcePlan,
    private val model: SharedPolynomialModel,
    private val scope: PolynomialInvocationScope,
    private val params: PolynomialStrategyParams,
    private val cancellation: Cancellation,
    private val parallelism: Int,
) {
    fun solve(engine: FiniteEngine): SolveResult = if (engine == FiniteEngine.MIXED) {
        portfolio().use { it.solve(cancellation) }
    } else standalone(engine) { it.solve(engine) }

    fun minimize(objective: LinearObjective, engine: FiniteEngine): MinimizeResult =
        if (engine == FiniteEngine.MIXED) {
            portfolio(objective).use { it.minimize(cancellation) }
        } else standalone(engine, objective) { it.minimize(objective, engine) }

    private fun <T> standalone(
        engine: FiniteEngine,
        objective: LinearObjective? = null,
        action: (PolynomialWorkerAdapter) -> T,
    ): T {
        val complete = engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.FIXED
        val budget = if (complete) params.backtrack.nodeBudget else params.localSearch.nodeBudget
        return PortfolioInvocationResources(1, 1, params.localSearch.randomSeed ?: 0,
            cancellation, budget, if (complete) setOf(0) else emptySet()).use { resources ->
            val slot = resources.slots.single()
            val execution = if (complete) params.backtrack.withAssumptions(params.assumptions).assumptions
                else params.localSearch.withAssumptions(params.assumptions).assumptions
            val binding = EngineInvocation(plan, ModelIdentity.of(model.source, objective), objective,
                params.assumptions, execution)
            action(PolynomialWorkerAdapter(model, scope.worker(slot), params, binding, scope.workerCancellation(slot)))
        }
    }

    private fun workerParams(slot: PortfolioWorkerSlot): PolynomialStrategyParams = params.copy(
        backtrack = params.backtrack.copy(randomSeed = slot.seed,
            maxDecisions = slot.nativeAllowance(params.backtrack.maxDecisions), nodeBudget = slot.nodeBudget),
        localSearch = params.localSearch.copy(randomSeed = slot.seed,
            maxFlips = slot.heuristicAllowance(params.localSearch.maxFlips),
            maxInstructions = params.localSearch.maxInstructions?.let(slot::heuristicAllowance),
            nodeBudget = slot.nodeBudget),
    )

    private fun portfolio(objective: LinearObjective? = null): PortfolioInvocation {
        val identity = ModelIdentity.of(model.source, objective)
        val binding = EngineInvocation(plan, identity, objective, sourceAssumptions = params.assumptions)
        val families = listOf(ArmFamily.Backtrack, ArmFamily.LocalSearch, ArmFamily.Lns)
        val factories = families.mapIndexed { arm, family ->
            PortfolioArmFactory(plan, arm, family) { slot ->
                val controls = workerParams(slot)
                val executionAssumptions = if (arm == 0) {
                    controls.backtrack.withAssumptions(controls.assumptions).assumptions
                } else controls.localSearch.withAssumptions(controls.assumptions).assumptions
                val workerBinding = EngineInvocation(plan, identity, objective, controls.assumptions,
                    executionAssumptions)
                PolynomialWorkerAdapter(model, scope.worker(slot), controls, workerBinding,
                    scope.workerCancellation(slot)).worker(arm, objective, slot).bindInvocation(workerBinding)
            }
        }
        return PortfolioInvocation(plan, factories,
            PortfolioInvocationControls(parallelism = parallelism,
                seed = params.localSearch.randomSeed ?: 0, cancellation = cancellation,
                nodeBudget = params.backtrack.nodeBudget ?: params.localSearch.nodeBudget, reseedStaleThreshold = 0),
            PortfolioEvidence(identity, polynomialWitnessVerifier(binding, cancellation))
                .bindInvocation(binding, cancellation), objective)
    }
}
