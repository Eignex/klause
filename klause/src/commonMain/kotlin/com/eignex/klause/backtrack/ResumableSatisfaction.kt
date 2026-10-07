package com.eignex.klause.backtrack

import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation

/**
 * The satisfaction driver for a single-threaded portfolio: one [CpSatisfactionTraversal] advanced a slice at a
 * time. A slice that ends pauses the traversal at decision granularity and keeps every learned clause, the trail
 * and the heuristics, so the arm resumes where it stopped instead of re-deriving them.
 *
 * Two tokens stop it, as in [ResumableMinimize]. The run's own token, handed to each [runSlice], ends the search;
 * propagation polls only that one, so a fixpoint is never stranded half-done for a later slice. The slice
 * boundary is polled at decisions alone.
 */
@Suppress("TooGenericExceptionCaught") // ownership boundaries preserve arbitrary primary and cleanup failures
internal class ResumableSatisfaction(private val solver: BacktrackSolver, params0: BacktrackParams) : ResumableSolve {
    private var globalToken: Cancellation = Cancellation.Never
    private val sink = SolveStatsSink(backend = "backtrack").also { it.start() }

    // Its LP reading waits for the traversal below; nothing arms the slice before the first [runSlice].
    private val slice: SliceBudget = SliceBudget({ sink.search.searchWork }, { traversal.lpWork() }, { traversal.propagationWork() })
    private val params = params0.copy(cancellation = Cancellation { globalToken() || slice.expired() })
    private val assumptions = params0.assumptions
    private val traversal: CpSatisfactionTraversal = CpSatisfactionTraversal(
        solver.problem,
        params,
        sink,
        solver.lpSolveContext,
        propagationCancellation = Cancellation { globalToken() },
        slice = TraversalSlice(pauses = { !globalToken() }, beforeBranch = {
            slice.charge()
            slice.workExpired()
        }),
    )

    private var done: SolveResult? = null
    private var closed = false

    override val isDone: Boolean get() = done != null

    override val stats: SolveStats get() = sink.snapshot()

    override val work: Long get() = slice.spent()

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
        done?.let { return it }
        check(!closed) { "search is closed" }
        globalToken = global
        if (!slice.begin(sliceMillis, sliceNodes)) return null
        traversal.fixedCancellationCadence = slice.workBounded
        try {
            val outcome = traversal.next()
            if (outcome == null) {
                slice.noteOverspend()
                // Restarts sample root fixings too, but an arm that restarts rarely would show none of its own.
                sink.search.observeRootFixed(traversal.rootFixedVariableCount())
                return null
            }
            return solver.verdictOf(outcome, assumptions, Cancellation { globalToken() }, sink).also {
                done = it
                close()
            }
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (done == null) sink.stop()
        traversal.close()
    }

    private fun closeAfter(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }
}
