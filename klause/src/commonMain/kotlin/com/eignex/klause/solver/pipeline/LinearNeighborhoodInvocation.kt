package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason

internal fun linearNeighborhoodInvocation(
    model: Problem,
    params: TheoryParams,
    objective: LinearObjective?,
    adaptive: Boolean,
    cores: Int,
): OpenTheoryExecution {
    val identity = ModelIdentity.of(model, objective)
    val count = cores.coerceAtLeast(1)
    val quota = if (count > 1) params.nodeBudget?.quotas(count) else null
    val native = params.strategyQuotas(count, complete = false)
    val workers = List(count) { index ->
        val local = native[index].copy(nodeBudget = quota?.get(index) ?: params.nodeBudget)
        val adapter = OpenExactNeighborhood(model, local, objective)
        exactNeighborhoodWorker(if (adaptive) "alns/exact-neighborhood" else "ls/exact-neighborhood", index, objective,
            params.cancellation or params.timeout, timeout = params.timeout, exhausted = { adapter.exhausted },
            open = { adapter.search(adaptive, params.randomSeed + index,
                maximumImprovementMoves = if (adaptive) params.alnsIterations.toLong() else null) })
            .bindEvidence(identity, provesResults = false)
    }
    val portfolio = Portfolio.thompson(workers, lanes = cores.coerceAtLeast(1))
    portfolio.evidenceVerification = PortfolioEvidence(identity, openWitnessVerifier(model, objective))
    val stop = params.cancellation or params.timeout
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
        val result = it.minimize(stop, null, PortfolioIncumbents.exact(objective))
        OpenTheoryExecution.Optimize(when (result) {
            is MinimizeResult.WithSample -> OpenTheoryOptimum.Bounded(
                OpenTheoryAssignment.Sampled(result.sample), objective.evaluateExact(result.sample),
                (result as? MinimizeResult.BestFound)?.reason ?: TerminationReason.BudgetExhausted, result.stats,
            )
            is MinimizeResult.Infeasible -> error("a heuristic neighborhood cannot refute the source")
            is MinimizeResult.Unknown -> OpenTheoryOptimum.Bounded(null, null, result.reason, result.stats)
        })
    }
}
