package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason

internal class CoreGuidedSolver(override val problem: BakedProblem) :
    Solver<BacktrackParams> by BacktrackSolver(problem),
    ResumableOptimizer<BacktrackParams> {
    override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch =
        CoreGuidedOptimizer(problem).resumable(objective, params)

    override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult =
        resumable(objective, params).use { handle ->
            var result: MinimizeResult? = null
            var incumbent: MinimizeResult.WithSample? = null
            while (result == null && !params.cancellation()) {
                result = handle.runSlice(params.cancellation, Long.MAX_VALUE, -1L) { incumbent = it }
            }
            result ?: incumbent?.let {
                MinimizeResult.BestFound(it.sample, it.objective, TerminationReason.Cancelled, handle.stats)
            } ?: MinimizeResult.Unknown(TerminationReason.Cancelled, handle.stats)
        }
}
