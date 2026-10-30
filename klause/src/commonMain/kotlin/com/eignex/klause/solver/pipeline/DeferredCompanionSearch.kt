package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.TimeSource

internal class DeferredCompanionSearch(
    private val unavailableReason: () -> TerminationReason,
    private val clock: TimeSource = TimeSource.Monotonic,
    private val open: (Cancellation, Long) -> ResumableSearch?,
) : InstructionSlicedSearch, ReseedGuard {
    private var search: ResumableSearch? = null
    private var terminal: MinimizeResult? = null
    private var closed = false
    override val isDone: Boolean get() = terminal != null
    override val canReseed: Boolean get() = false
    override val incumbentTrialPending: Boolean get() = !isDone &&
        (search == null || (search as? ReseedGuard)?.incumbentTrialPending == true)
    override val stats: SolveStats get() = search?.stats ?: SolveStats.EMPTY
    override val preparationPending: Boolean get() = search?.preparationPending ?: !isDone
    override val work: Long get() = search?.work ?: 0L
    override val chargedInstructions: Long get() = (search as? InstructionSlicedSearch)?.chargedInstructions ?: work

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
        advance(global, sliceMillis) { search, millis -> search.runSlice(global, millis, sliceNodes, onIncumbent) }

    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = advance(global, sliceMillis) { search, millis ->
        if (search is InstructionSlicedSearch) search.runInstructionSlice(global, millis, sliceInstructions, onIncumbent)
        else search.runSlice(global, millis, sliceInstructions, onIncumbent)
    }

    private fun advance(global: Cancellation, sliceMillis: Long,
        run: (ResumableSearch, Long) -> MinimizeResult?): MinimizeResult? {
        terminal?.let { return it }
        check(!closed)
        if (sliceMillis <= 0L) return null
        val started = clock.markNow()
        val current = search ?: if (global.isCancelled()) null else open(global, sliceMillis)?.also { search = it }
        if (current == null) {
            return MinimizeResult.Unknown(unavailableReason(), stats).also { terminal = it }
        }
        val available = (sliceMillis - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
        if (available == 0L) return null
        return run(current, available).also { terminal = it }
    }

    override fun close() {
        if (closed) return
        closed = true
        search?.close()
    }
}
