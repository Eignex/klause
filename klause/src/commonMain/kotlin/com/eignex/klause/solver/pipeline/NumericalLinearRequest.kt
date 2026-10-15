package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.hasFiniteIntegerRanges
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.uuid.Uuid

/** Numerical LP/MIP execution with source certification and no arithmetic-theory fallback. */
class NumericalLinearRequest(
    private val model: Problem,
    /** Original-direction objective, or null for feasibility. */
    val objective: LinearObjective? = null,
    /** Whether the original objective is maximized. */
    val maximize: Boolean = false,
    private val cancellation: Cancellation = Cancellation.Never,
) {
    /** Existing finite execution when every integer range and numerical completion is supported. */
    fun finiteRoute(onLpStats: (LpStats) -> Unit = {}): SourceProblemRoute.Finite? {
        if (cancellation() || !model.hasFiniteIntegerRanges() || !model.finiteRealCompletionSupported()) return null
        return model.pipelineRoute(objective, maximize, boundCancellation = cancellation,
            onLpStats = onLpStats) as? SourceProblemRoute.Finite
    }

    /**
     * Traverse source integer splits using numerical relaxations and checked source derivations.
     * Unresolved leaves retain witnesses and root bounds without a terminal claim.
     * [complete] withholds bounds and terminals for witness-only callers.
     */
    fun solve(
        params: TheoryParams = TheoryParams(),
        complete: Boolean = true,
        onIncumbent: (Sample, BigFraction) -> Unit = { _, _ -> },
    ): LinearOptimizationResult {
        val stop = cancellation or params.timeout or params.cancellation
        val context = model.linearAuthorityContext(objective ?: LinearObjective(),
            if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE, stop)
        if (context == null) {
            val identity = Uuid.random().toString()
            val scope = SourceScope(SourceRevision(identity, "klause-ir-exact-v1"),
                objective = SourceObjective("$identity/objective",
                    if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE))
            val reason = when {
                params.timeout() -> TerminationReason.Timeout
                stop() -> TerminationReason.Cancelled
                else -> TerminationReason.Unsupported
            }
            return LinearOptimizationResult(scope, null, null, null, null, reason, SolveStats.EMPTY)
        }
        return LinearAuthorityInvocation(context, params.copy(cancellation = stop)).use { invocation ->
            val result = requireNotNull(invocation.runSlice(Cancellation.Never, Long.MAX_VALUE, -1, onIncumbent))
            LinearOptimizationResult(context.scope, result.terminal.takeIf { complete }, result.bound.takeIf { complete },
                result.incumbent,
                result.value, result.reason, result.stats.copy(run = result.stats.run.copy(backend = "numerical-mip")))
        }
    }
}
