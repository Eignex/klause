package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.solver.evidence.quadraticEngineInvocation
import com.eignex.klause.solver.result.QuadraticOptimizationResult

internal class QuadraticStrategyAdapters(
    private val request: PolynomialTheoryRequest,
    private val model: SharedPolynomialModel,
    private val objective: QuadraticObjectiveSpec,
    private val sense: ObjectiveSense,
    private val params: PolynomialTheoryParams,
    private val native: QuadraticNativeScope,
    private val engine: FiniteEngine,
    private val cores: Int = 1,
    private val publication: QuadraticPublicationObserver? = null,
) {
    fun execute(): QuadraticOptimizationResult {
        val assumptions = quadraticAssumptions(params, engine)
        if (engine == FiniteEngine.FIXED || (engine != FiniteEngine.MIXED && cores == 1)) {
            return QuadraticWorkerAdapter(request, model, objective, sense, params, native, engine).also { worker ->
                worker.onAccepted = publication?.let { observer -> { point -> observer.accepted(point) } }
            }.execute()
        }
        val plan = request.preparedPlan.withObjective(objective, sense)
        val invocation = request.preparedPlan.quadraticEngineInvocation(objective, sense, assumptions)
        return QuadraticPortfolioInvocation(plan, objective, sense, params, native, engine, cores,
            assumptions, invocation)
            .execute(onAccepted = { publication?.accepted(it.objective, it.version) }) { arm, slot, scope, read ->
                val controls = params.copy(maximumWork = null, cancellation = scope.polynomial,
                    strategy = params.strategy.copy(
                        backtrack = params.strategy.backtrack.copy(randomSeed = slot.seed,
                            maxDecisions = slot.nativeAllowance(params.strategy.backtrack.maxDecisions),
                            nodeBudget = slot.nodeBudget),
                        localSearch = params.strategy.localSearch.copy(randomSeed = slot.seed,
                            maxFlips = slot.heuristicAllowance(params.strategy.localSearch.maxFlips),
                            maxInstructions = params.strategy.localSearch.maxInstructions
                                ?.let(slot::heuristicAllowance),
                            nodeBudget = slot.nodeBudget)))
                val selected = when (arm) {
                    0 -> FiniteEngine.BACKTRACK
                    1 -> FiniteEngine.LOCAL_SEARCH
                    else -> FiniteEngine.ALNS
                }
                val worker = slot.own(QuadraticWorkerAdapter(request, model, objective, sense, controls, scope,
                    selected))
                worker.readIncumbent = read
                worker.worker(arm)
            }
    }
}
