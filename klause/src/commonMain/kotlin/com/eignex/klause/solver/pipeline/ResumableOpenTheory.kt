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
 * between slices, for a portfolio to schedule beside local search.
 *
 * Setup — preparation, bound closing and the root — runs whole in the first slice: cut short it would decide
 * nothing and have to start over, and one longer than every slice would then never finish. After it a slice ends
 * only at a branch, once its [OpenTheoryWorkStats.openWork][com.eignex.klause.solver.result.OpenTheoryWorkStats.openWork]
 * share or its time is spent and at least one branch was expanded in it, so a theory check is never cut by a slice
 * and every slice moves the search. The global token passed to each [runSlice] stops it for good.
 *
 * [state] is the solve-wide state the run charges its work and statistics to, shared when several runs make up one
 * solve.
 */
internal class ResumableOpenTheory(
    private val engine: OpenTheoryEngine,
    private val params: TheoryParams,
    private val state: OpenTheorySolveState = OpenTheorySolveState(params),
) : AutoCloseable {
    private var globalToken: Cancellation = Cancellation.Never
    private var sliceEndWork = Long.MAX_VALUE
    private var sliceEnd: ComparableTimeMark? = null
    private var expandedThisSlice = false
    private var run: OpenTheoryEngine.OpenTheoryRun? = null
    private var verdict: OpenTheoryResult? = null

    private val pausing = object : SearchNodePolicy {
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
            if (expandedThisSlice && !globalToken() && sliceSpent()) return SearchNodeDisposition.Pause
            expandedThisSlice = true
            return SearchNodeDisposition.Expand
        }
    }

    private fun sliceSpent(): Boolean = state.work.spent >= sliceEndWork || sliceEnd?.hasPassedNow() == true

    /** Whether the search has reached its verdict. */
    val isDone: Boolean get() = verdict != null

    /** Work spent so far on [state], in `openWork` units. */
    val work: Long get() = state.work.spent

    /** The verdict's stats once decided, else the work counters so far. */
    val stats: SolveStats get() = verdict?.stats ?: SolveStats(openTheory = state.work.snapshot())

    /**
     * Advance until a verdict, [global] firing, or the slice ending: after [sliceWork] `openWork` units when
     * non-negative, or after [sliceMillis], whichever comes first. The verdict once there is one, else null with
     * the search paused.
     */
    fun runSlice(global: Cancellation, sliceMillis: Long, sliceWork: Long): OpenTheoryResult? {
        verdict?.let { return it }
        globalToken = global
        sliceEndWork = if (sliceWork >= 0L) state.work.spent + sliceWork else Long.MAX_VALUE
        sliceEnd =
            if (sliceMillis == Long.MAX_VALUE) null else TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        expandedThisSlice = false
        val current = run ?: when (val begun = begin()) {
            is OpenTheoryStart.Decided -> return decide(begun.result)
            is OpenTheoryStart.Running -> begun.run.also { run = it }
        }
        val result = current.advance() ?: return null
        return decide(result)
    }

    private fun begin(): OpenTheoryStart {
        val stop = Cancellation { globalToken() }
        return engine.begin(params.copy(cancellation = params.cancellation or stop), state, pausing)
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
