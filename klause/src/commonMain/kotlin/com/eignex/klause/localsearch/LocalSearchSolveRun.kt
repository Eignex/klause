package com.eignex.klause.localsearch

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.withSourceSemantics
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
    private val semantics: EffectiveSourceSemantics,
) : LocalSearchRun<SolveResult>(sink, control, release), InstructionSlicedSolve {
    override val work: Long get() = control.work

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
        executeSlice(global, sliceMillis, LocalSearchRunControl.instructionAllowance(sliceNodes), sliceNodes)

    override fun runInstructionSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
    ): SolveResult? = executeSlice(global, sliceMillis, sliceInstructions, -1L)

    private fun executeSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
        sliceNodes: Long,
    ): SolveResult? = runCatching {
        advance(global, sliceMillis, sliceInstructions, sliceNodes)
    }.getOrElse {
        close()
        throw it
    }

    private fun advance(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
        sliceNodes: Long,
    ): SolveResult? {
        verdict?.let { return it }
        checkOpen()
        if (!supported) return unknown(TerminationReason.Unsupported)
        if (!hasEffectiveAssumptions) {
            return if (refutesModel) {
                sink.stop()
                finish(SolveResult.Unsat(stats = stats).withSourceSemantics(semantics))
            } else {
                unknown(TerminationReason.Unsupported)
            }
        }
        val available = params.nodeBudget?.allowance?.movesLeft() ?: Long.MAX_VALUE
        if (available == 0L) return unknown(TerminationReason.BudgetExhausted)
        control.offer(global, sliceMillis, sliceInstructions, available, sliceNodes)
        if (control.instructions >= control.limit || control.token() || !control.hasSliceAllowance) return null
        val live = checkNotNull(cursor)
        if (!live.hasNext()) return unknown(TerminationReason.BudgetExhausted)
        val sample = live.next() ?: return if (control.instructions >= maxInstructions) {
            unknown(TerminationReason.BudgetExhausted)
        } else {
            null
        }
        sink.stop()
        return finish(SolveResult.Sat(sample, stats).withSourceSemantics(semantics))
    }

    private fun unknown(reason: TerminationReason): SolveResult {
        sink.timedOut = reason == TerminationReason.BudgetExhausted
        sink.stop()
        return finish(SolveResult.Unknown(reason, stats).withSourceSemantics(semantics))
    }

    override fun close() = super<LocalSearchRun>.close()

    override fun discardCursor() {
        cursor = null
    }
}
