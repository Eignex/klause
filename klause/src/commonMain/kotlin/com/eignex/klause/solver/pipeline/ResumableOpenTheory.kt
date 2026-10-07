package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.util.Cancellation
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * An open theory search advanced a slice at a time, its decision stack, learned clauses and theory state kept
 * between slices, for an open portfolio to schedule beside local search.
 *
 * Preparation and bound closing run in the first slice, under the run's own token. After that a slice ends only at a
 * branch — once its [OpenTheoryWorkStats.openWork][com.eignex.klause.solver.result.OpenTheoryWorkStats.openWork]
 * share is spent, or its time when it counts none — so a theory check is never cut by a slice and the search resumes
 * exactly where it stopped. The global token passed to each [runSlice] stops it for good.
 */
internal class ResumableOpenTheory(private val engine: OpenTheoryEngine, private val params: TheoryParams) :
    AutoCloseable {
    private val state = OpenTheorySolveState(params)
    private var globalToken: Cancellation = Cancellation.Never
    private var sliceEndWork = Long.MAX_VALUE
    private var sliceEnd: ComparableTimeMark? = null
    private var run: OpenTheoryEngine.OpenTheoryRun? = null
    private var started = false
    private var verdict: OpenTheoryResult? = null

    private val pausing = object : SearchNodePolicy {
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition =
            if (!globalToken() && sliceSpent()) SearchNodeDisposition.Pause else SearchNodeDisposition.Expand
    }

    private fun sliceSpent(): Boolean = state.work.spent >= sliceEndWork || sliceEnd?.hasPassedNow() == true

    /** Whether the search has reached its verdict. */
    val isDone: Boolean get() = verdict != null

    /** Work spent so far, in `openWork` units. */
    val work: Long get() = state.work.spent

    /** The verdict's stats once decided, else the work counters so far. */
    val stats: SolveStats get() = verdict?.stats ?: SolveStats(openTheory = state.work.snapshot())

    /**
     * Advance until a verdict, [global] firing, or the slice ending: after [sliceWork] `openWork` units when
     * non-negative, else after [sliceMillis]. The verdict once there is one, else null with the search paused.
     */
    fun runSlice(global: Cancellation, sliceMillis: Long, sliceWork: Long): OpenTheoryResult? {
        verdict?.let { return it }
        globalToken = global
        if (sliceWork >= 0L) {
            sliceEndWork = state.work.spent + sliceWork
            sliceEnd = null
        } else {
            sliceEndWork = Long.MAX_VALUE
            sliceEnd = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        }
        if (!started) {
            started = true
            val stop = Cancellation { globalToken() }
            when (val begun = engine.begin(params.copy(cancellation = params.cancellation or stop), state, pausing)) {
                is OpenTheoryStart.Decided -> return decide(begun.result)
                is OpenTheoryStart.Running -> run = begun.run
            }
        }
        val result = checkNotNull(run).advance() ?: return null
        return decide(result)
    }

    private fun decide(result: OpenTheoryResult): OpenTheoryResult {
        verdict = result
        close()
        return result
    }

    override fun close() {
        run?.close()
        run = null
    }
}
