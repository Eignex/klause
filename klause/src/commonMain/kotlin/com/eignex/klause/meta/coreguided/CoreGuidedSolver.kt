package com.eignex.klause.meta.coreguided

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Session
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.StatelessSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.withSourceSemantics

internal class CoreGuidedSolver(
    override val problem: BakedProblem,
    private val maximumWork: Long? = null,
    private val maximumMillis: Long? = null,
    private val coreRefinement: CoreRefinementMode = CoreRefinementMode.NONE,
) :
    Solver<BacktrackParams> by BacktrackSolver(problem),
    ResumableOptimizer<BacktrackParams> {
    override fun session(): Session<BacktrackParams> = StatelessSession(this)

    override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch =
        CoreGuidedSearch(problem, objective, params, maximumWork = maximumWork, maximumMillis = maximumMillis,
            coreRefinement = coreRefinement)

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
        }.withSourceSemantics(problem.semantics)
}
