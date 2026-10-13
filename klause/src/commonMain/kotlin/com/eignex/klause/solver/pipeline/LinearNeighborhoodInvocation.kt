package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult

internal fun linearNeighborhoodInvocation(
    model: Problem,
    params: TheoryParams,
    objective: LinearObjective?,
    adaptive: Boolean,
    cores: Int,
    preparedSource: PreparedSourcePlan? = null,
    sourceObjective: OpenSourceObjective? = null,
    publication: SourcePublicationObserver? = null,
): OpenTheoryExecution {
    val count = cores.coerceAtLeast(1)
    val prepared = preparedSource ?: model.prepareExecutionPlan(objective)
    val source = prepared.originalSource as? Problem ?: model
    val identity = ModelIdentity.of(source, objective)
    val context = sourceObjective?.context
    val binding = EngineInvocation(prepared, identity, objective,
        scope = context?.scope, condition = context?.scope?.condition)
    val stop = params.cancellation or params.timeout
    val factories = List(count) { index ->
        PortfolioArmFactory(prepared, index, if (adaptive) ArmFamily.Lns else ArmFamily.LocalSearch,
            bind = { it.bindInvocation(binding) }) { slot ->
            val local = params.copy(openWorkLimit = slot.nativeAllowance(params.openWorkLimit),
                maxLeaves = slot.nativeAllowance(params.maxLeaves),
                maxDecisions = slot.nativeAllowance(params.maxDecisions),
                maxFlips = slot.heuristicAllowance(params.maxFlips),
                maxInstructions = params.maxInstructions?.let(slot::heuristicAllowance),
                nodeBudget = slot.nodeBudget)
            val adapter = slot.own(OpenExactNeighborhood(source, local, objective, prepared))
            exactNeighborhoodWorker(if (adaptive) "alns/exact-neighborhood" else "ls/exact-neighborhood", index,
                objective, stop, timeout = params.timeout, exhausted = { adapter.exhausted },
                open = { adapter.search(adaptive, slot.seed,
                    maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null) })
                .bindEvidence(identity, provesResults = false)
        }
    }
    val portfolio = PortfolioInvocation(prepared, factories,
        PortfolioInvocationControls(parallelism = count, workerSeed = params.randomSeed, cancellation = stop,
            nodeBudget = params.nodeBudget),
        PortfolioEvidence(identity, openWitnessVerifier(source, objective, sourceObjective?.context, stop))
            .bindInvocation(binding, stop))
    return portfolio.use {
        if (objective == null) {
            val result = it.solve(stop)
            return@use OpenTheoryExecution.Satisfy(when (result) {
                is SolveResult.Sat -> OpenTheoryResult.Sat(
                    OpenTheoryAssignment.Sampled(result.assignment), result.stats,
                )
                is SolveResult.Unsat -> error("a heuristic neighborhood cannot refute the source")
                is SolveResult.Unknown -> OpenTheoryResult.Unknown(result.reason, result.stats)
            })
        }
        val values = sourceObjective ?: OpenSourceObjective(objective)
        val incumbents = values.incumbents()
        val result = it.minimize(stop, null, incumbents, onAccepted = { accepted ->
            publication?.accepted(accepted.assignment,
                SourceValue.Rational(accepted.objective.source), accepted.version)
        })
        check(result !is MinimizeResult.Infeasible) { "a heuristic neighborhood cannot refute the source" }
        OpenTheoryExecution.Optimize(values.incomplete(result, incumbents))
    }
}
