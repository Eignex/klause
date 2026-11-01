package com.eignex.klause.localsearch

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.withSourceSemantics
import com.eignex.klause.util.Cancellation

internal class LocalSearchOptimizeRun(
    private val params: LocalSearchParams,
    private var cursor: Iterator<MinimizeResult?>?,
    sink: SolveStatsSink,
    control: LocalSearchRunControl,
    release: () -> Unit,
    private val semantics: EffectiveSourceSemantics,
) : LocalSearchRun<MinimizeResult>(sink, control, release), InstructionSlicedSearch {
    private var started = false

    override val work: Long get() = control.work

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = executeSlice(
        global, sliceMillis, LocalSearchRunControl.instructionAllowance(sliceNodes), sliceNodes, onIncumbent,
    )

    override fun runInstructionSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = executeSlice(global, sliceMillis, sliceInstructions, -1L, onIncumbent)

    private fun executeSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = runCatching {
        advance(global, sliceMillis, sliceInstructions, sliceNodes, onIncumbent)
    }.getOrElse {
        close()
        throw it
    }

    private fun advance(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        verdict?.let { return it }
        checkOpen()
        val available = params.nodeBudget?.allowance?.movesLeft() ?: Long.MAX_VALUE
        control.offer(global, sliceMillis, sliceInstructions, available, sliceNodes)
        if (control.token() || sliceInstructions == 0L) return null
        if (available == 0L) {
            if (!started) {
                sink.timedOut = true
                sink.stop()
                return finish(MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)
                    .withSourceSemantics(semantics))
            }
            // The suspended loop checks its containing allowance before making another move.
            control.allowCompletion()
        } else if (!control.hasSliceAllowance) return null
        started = true
        val live = checkNotNull(cursor)
        while (live.hasNext()) {
            val result = live.next()?.withSourceSemantics(semantics) ?: return null
            if (control.finished) return finish(result)
            if (result is MinimizeResult.WithSample) onIncumbent(result)
        }
        check(control.finished) { "the local-search stream ended without a verdict" }
        return verdict
    }

    override fun close() = super<LocalSearchRun>.close()

    override fun discardCursor() {
        cursor = null
    }
}
