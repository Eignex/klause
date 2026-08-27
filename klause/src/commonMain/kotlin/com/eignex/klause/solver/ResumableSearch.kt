package com.eignex.klause.solver

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation

/**
 * A pause/resume handle over a branch-and-bound optimisation. Unlike [Optimizer.improvements] — whose
 * `Sequence` rebuilds the engine on every call and so loses all learned state between calls — a
 * [ResumableSearch] holds the **entire search state explicitly** in object fields: the live
 * propagation state (learned-clause database, the DFS trail), the variable/value heuristics, the
 * incumbent, phase-saving, and LP warm-start caches. [runSlice] advances that state for one time
 * slice and returns; a later [runSlice] **continues the exact search mid-tree**, with everything
 * intact, instead of starting over.
 *
 * This is the engine seam a single-threaded portfolio ([com.eignex.klause.portfolio.SequentialPortfolio])
 * needs to schedule an arm in segments without the cold-restart re-learning that dominated its
 * time-to-best: each scheduled segment resumes the arm where the previous one paused.
 *
 * No coroutine suspension is involved — the search loop is a sequence of atomic steps gated by a
 * top-of-loop slice check, so returning at that check and re-entering from the top is a faithful
 * resume given the retained fields. The handle is **single-threaded and stateful**; drive it from one
 * thread, one [runSlice] at a time.
 *
 * Obtain one from a [ResumableOptimizer]. [close] releases any per-search resources.
 */
interface ResumableSearch : AutoCloseable {
    /**
     * Advance the search for up to [sliceMillis] more wall-clock milliseconds, or until [global]
     * fires, or the search completes — whichever comes first — resuming exactly where the previous
     * call left off. Every **new** incumbent discovered during this slice is passed to [onIncumbent]
     * as it lands (objective strictly improving on the best seen so far).
     *
     * Returns the **terminal verdict** ([MinimizeResult.Optimal] / [MinimizeResult.Infeasible], or a
     * [MinimizeResult.BestFound] / [MinimizeResult.Unknown] with
     * [com.eignex.klause.solver.result.TerminationReason.SearchExhausted] when an external bound
     * supplier makes the absolute proof unsound from this arm's vantage) if the search **completed**
     * during this slice; otherwise `null` — the slice elapsed (or [global] fired) with search still
     * pending, so call [runSlice] again to continue. Once a terminal verdict is returned, [isDone] is
     * `true` and further calls return that same verdict without doing work.
     */
    fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = runSlice(global, sliceMillis, sliceNodes = -1L, onIncumbent)

    /**
     * As [runSlice], but ending the slice after [sliceNodes] search nodes rather than after
     * [sliceMillis] when [sliceNodes] is non-negative.
     *
     * A slice measured in nodes is reproducible: the same invocation pauses at the same point in the
     * same tree, so the counters a run reports do not depend on how loaded the machine was. A slice
     * measured in milliseconds cannot be — it lands somewhere different every time, and every statistic
     * downstream of the search inherits that. [sliceMillis] still applies as the outer bound, so a
     * node budget that turns out to be enormous cannot overrun the deadline.
     */
    fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult?

    /** True once [runSlice] has returned a terminal verdict; further calls are no-ops. */
    val isDone: Boolean

    /**
     * Counters accumulated so far, whether or not the search has finished.
     *
     * A paused slice still did the work it did, so a caller reporting a run its deadline cut short reads
     * them here — the terminal verdict it would otherwise take them from is exactly what such a run never
     * produces. Cumulative for this handle, so a caller folds each handle once rather than per slice.
     */
    val stats: SolveStats

    override fun close() {}
}

/**
 * An [Optimizer] that can hand out a [ResumableSearch] over a given objective — i.e. one whose B&B
 * search state can be paused and resumed across slices. [com.eignex.klause.backtrack.BacktrackSolver]
 * implements this; local search does not (it restarts cheaply from a warm-started incumbent instead).
 */
interface ResumableOptimizer<P : SolverParams> : Optimizer<P> {
    /** Open a fresh [ResumableSearch] minimising [objective] under [params]. The returned handle owns
     *  its own slice cancellation; any cancellation token already on [params] (see
     *  [SolverParams.withCancellation]) is superseded per slice by [ResumableSearch.runSlice]'s `global`
     *  token. */
    fun resumable(objective: LinearObjective, params: P): ResumableSearch
}

/**
 * A reusable handle for solving a sequence of pinned sub-problems on one persistent search — the LNS
 * destroy/repair loop. Each [repair] re-seeds the same session and LP relaxation on a new
 * assumption set instead of rebuilding, so the learned-clause database and LP warm start carry across
 * fragments. Single-threaded and stateful; obtain one from a backtrack solver.
 */
internal interface RepairSearch : AutoCloseable {
    /**
     * Solve the fragment pinned by [assumptions] under a [decisionBudget] (decisions), pruning against
     * [cutoff]. The caller MUST keep [cutoff] monotone non-increasing across calls — the reused session
     * retains permanent objective-bound clauses, so a monotone cutoff keeps every stale bound looser than
     * the current one (never a wrong prune). Returns the best strictly-better completion found, or null.
     */
    fun repair(assumptions: Assumptions, decisionBudget: Long, cutoff: Double): Sample?

    override fun close() {}
}
