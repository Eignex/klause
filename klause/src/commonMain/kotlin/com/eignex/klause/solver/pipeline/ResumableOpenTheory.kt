package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.util.Cancellation
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An open theory search advanced a slice at a time, its decision stack, learned clauses and theory state kept
 * between slices, for an open portfolio to schedule beside local search.
 *
 * Setup — preparation, bound closing and the root — runs under the slice's clock: one that does not finish in its
 * slice is dropped and starts over in the next, as a rerun arm would, rather than hold the lane. Once the search is
 * running a slice ends only at a branch, when its
 * [OpenTheoryWorkStats.openWork][com.eignex.klause.solver.result.OpenTheoryWorkStats.openWork] share or its
 * [sliceTimeCap] is spent, so a theory check is never cut by a slice and the search resumes exactly where it stopped.
 * The global token passed to each [runSlice] stops it for good.
 */
internal class ResumableOpenTheory(
    private val engine: OpenTheoryEngine,
    private val params: TheoryParams,
    /** Wall-clock ceiling on one slice beside its work share: a few `openWork` units can be seconds of checks. */
    private val sliceTimeCap: Duration = DEFAULT_SLICE_TIME_CAP,
) : AutoCloseable {
    private var state = OpenTheorySolveState(params)
    private var globalToken: Cancellation = Cancellation.Never
    private var sliceEndWork = Long.MAX_VALUE
    private var sliceEnd: ComparableTimeMark? = null
    private var run: OpenTheoryEngine.OpenTheoryRun? = null
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
     * non-negative, else after [sliceMillis], and in either case by [sliceTimeCap]. The verdict once there is one,
     * else null with the search paused.
     */
    fun runSlice(global: Cancellation, sliceMillis: Long, sliceWork: Long): OpenTheoryResult? {
        verdict?.let { return it }
        globalToken = global
        val now = TimeSource.Monotonic.markNow()
        sliceEndWork = if (sliceWork >= 0L) state.work.spent + sliceWork else Long.MAX_VALUE
        val end = now + if (sliceWork >= 0L) sliceTimeCap else minOf(sliceMillis.milliseconds, sliceTimeCap)
        sliceEnd = end
        val current = run ?: when (val begun = begin(end)) {
            null -> return null
            is OpenTheoryStart.Decided -> return decide(begun.result)
            is OpenTheoryStart.Running -> begun.run.also { run = it }
        }
        val result = current.advance() ?: return null
        return decide(result)
    }

    // Set the search up under the slice's clock; null when the clock cut setup short, which decides nothing.
    private fun begin(end: ComparableTimeMark): OpenTheoryStart? {
        val stop = Cancellation { globalToken() }
        val setupStop = Cancellation { end.hasPassedNow() }
        val begun = engine.begin(params.copy(cancellation = params.cancellation or stop or setupStop), state, pausing)
        val cutShort = begun is OpenTheoryStart.Decided && begun.result is OpenTheoryResult.Unknown
        if (cutShort && !globalToken() && setupStop()) {
            state = OpenTheorySolveState(params)
            return null
        }
        return begun
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

    private companion object {
        // One slice's wall-clock ceiling: the portfolio's own first time slice for an arm with no work counter.
        val DEFAULT_SLICE_TIME_CAP: Duration = 2.seconds
    }
}
