package com.eignex.klause.solver

import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult

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
 * time-to-best (#381): each scheduled segment resumes the arm where the previous one paused.
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
    ): MinimizeResult?

    /** True once [runSlice] has returned a terminal verdict; further calls are no-ops. */
    val isDone: Boolean

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
