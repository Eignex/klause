package com.eignex.klause.solver.pipeline

import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration.Companion.milliseconds

internal fun exactNeighborhoodWorker(
    label: String,
    armId: Int,
    objective: LinearObjective?,
    lifetime: Cancellation,
    timeout: Cancellation = Cancellation.Never,
    exhausted: () -> Boolean,
    open: () -> ExactNeighborhood,
): PortfolioWorker {
    fun stop(global: Cancellation) = lifetime or global or Cancellation(exhausted)
    fun slice(global: Cancellation, millis: Long) = stop(global) or
        if (millis == Long.MAX_VALUE) Cancellation.Never else Cancellation.after(millis.milliseconds)
    fun reason(global: Cancellation) = if (timeout()) TerminationReason.Timeout
        else if ((lifetime or global)()) TerminationReason.Cancelled
        else TerminationReason.BudgetExhausted
    if (objective == null) return PortfolioWorker.ofSolve(label, armId, countsInstructions = true,
        resumable = {
            object : InstructionSlicedSolve {
                private val search = open()
                private var closed = false
                private var result: SolveResult? = null
                override val isDone: Boolean get() = result != null
                override val stats get() = search.stats
                override val work get() = search.work
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
                    runInstructionSlice(global, sliceMillis, sliceNodes)
                override fun runInstructionSlice(
                    global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
                ): SolveResult? {
                    result?.let { return it }
                    check(!closed)
                    val done = search.advance(slice(global, sliceMillis),
                        if (sliceInstructions < 0) Long.MAX_VALUE else sliceInstructions)
                    if (done || stop(global)()) result = search.solveResult(reason(global))
                    return result
                }
                override fun close() { closed = true }
            }
        }, solve = { global, budget ->
            val search = open()
            search.advance(stop(global), budget ?: Long.MAX_VALUE)
            search.solveResult(reason(global))
        }).also { if (label.startsWith("alns")) it.family = ArmFamily.Lns }
    return PortfolioWorker.ofMinimize(label, armId, countsInstructions = true,
        resumable = {
            object : InstructionSlicedSearch, ReseedGuard {
                override val canReseed: Boolean get() = false
                private val search = open()
                private var closed = false
                private var result: MinimizeResult? = null
                override val isDone: Boolean get() = result != null
                override val stats get() = search.stats
                override val work get() = search.work
                override val chargedInstructions get() = search.work
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
                    runInstructionSlice(global, sliceMillis, sliceNodes, onIncumbent)
                override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                    result?.let { return it }
                    check(!closed)
                    val done = search.advance(slice(global, sliceMillis),
                        if (sliceInstructions < 0) Long.MAX_VALUE else sliceInstructions) { sample, value ->
                        onIncumbent(MinimizeResult.BestFound(sample, requireNotNull(value).toDouble(),
                            TerminationReason.BudgetExhausted, search.stats))
                    }
                    if (done || stop(global)()) result = search.minimizeResult(reason(global))
                    return result
                }
                override fun close() { closed = true }
            }
        }, improvements = { _, _, global, budget ->
            val search = open()
            search.advance(stop(global), budget ?: Long.MAX_VALUE)
            sequenceOf(search.minimizeResult(reason(global)))
        }).also { if (label.startsWith("alns")) it.family = ArmFamily.Lns }
}
