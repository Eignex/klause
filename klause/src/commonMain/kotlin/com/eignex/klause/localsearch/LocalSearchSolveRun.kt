package com.eignex.klause.localsearch

import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

@Suppress("LongParameterList")
internal class LocalSearchSolveRun(
    private val params: LocalSearchParams,
    private val supported: Boolean,
    private val hasEffectiveAssumptions: Boolean,
    private val refutesModel: Boolean,
    private val maxInstructions: Long,
    private var cursor: Iterator<Sample?>?,
    sink: SolveStatsSink,
    control: LocalSearchRunControl,
    release: () -> Unit,
) : LocalSearchRun<SolveResult>(sink, control, release), InstructionSlicedSolve {
    override val work: Long get() = control.work

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
        runInstructionSlice(global, sliceMillis, LocalSearchRunControl.instructionAllowance(sliceNodes))

    override fun runInstructionSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
    ): SolveResult? = runCatching {
        advance(global, sliceMillis, sliceInstructions)
    }.getOrElse {
        close()
        throw it
    }

    private fun advance(global: Cancellation, sliceMillis: Long, sliceInstructions: Long): SolveResult? {
        verdict?.let { return it }
        checkOpen()
        if (!supported) return unknown(TerminationReason.Unsupported)
        if (!hasEffectiveAssumptions) {
            return if (refutesModel) {
                sink.stop()
                finish(SolveResult.Unsat(stats = stats))
            } else {
                unknown(TerminationReason.Unsupported)
            }
        }
        val available = params.nodeBudget?.movesLeft() ?: Long.MAX_VALUE
        if (available == 0L) return unknown(TerminationReason.BudgetExhausted)
        control.offer(global, sliceMillis, sliceInstructions, available)
        if (control.instructions >= control.limit || control.token()) return null
        val live = checkNotNull(cursor)
        if (!live.hasNext()) return unknown(TerminationReason.BudgetExhausted)
        val sample = live.next() ?: return if (control.instructions >= maxInstructions) {
            unknown(TerminationReason.BudgetExhausted)
        } else {
            null
        }
        sink.stop()
        return finish(SolveResult.Sat(sample, stats))
    }

    private fun unknown(reason: TerminationReason): SolveResult {
        sink.timedOut = reason == TerminationReason.BudgetExhausted
        sink.stop()
        return finish(SolveResult.Unknown(reason, stats))
    }

    override fun discardCursor() {
        cursor = null
    }
}
