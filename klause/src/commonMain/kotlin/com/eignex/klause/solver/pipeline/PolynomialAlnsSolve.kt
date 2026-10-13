package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation

internal class PolynomialAlnsSolve(private val search: ResumableSearch) : InstructionSlicedSolve, ReseedGuard {
    private var result: SolveResult? = null
    override val canReseed: Boolean get() = false
    override val isDone: Boolean get() = result != null
    override val stats get() = search.stats
    override val work get() = search.work
    override val initialWork get() = search.initialWork

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
        advance { callback -> search.runSlice(global, sliceMillis, sliceNodes, callback) }

    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long): SolveResult? =
        advance { callback ->
            val counted = search as? InstructionSlicedSearch
            if (counted != null) counted.runInstructionSlice(global, sliceMillis, sliceInstructions, callback)
            else search.runSlice(global, sliceMillis, sliceInstructions, callback)
        }

    private fun advance(run: ((MinimizeResult.WithSample) -> Unit) -> MinimizeResult?): SolveResult? {
        result?.let { return it }
        val terminal = run { candidate -> result = SolveResult.Sat(candidate.sample, candidate.stats) }
        result = result?.let { SolveResult.Sat(requireNotNull(it.assignment), stats) }
            ?: terminal?.assignment?.let { SolveResult.Sat(it, stats) }
            ?: if (terminal != null) SolveResult.Unknown(TerminationReason.BudgetExhausted, stats) else null
        return result
    }

    override fun close() = search.close()
}
