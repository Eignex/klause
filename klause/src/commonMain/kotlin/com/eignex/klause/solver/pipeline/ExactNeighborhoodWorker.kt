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
import com.eignex.klause.util.ExecutionControl
import kotlin.time.Duration.Companion.milliseconds

internal fun exactNeighborhoodWorker(
    label: String,
    armId: Int,
    objective: LinearObjective?,
    lifetime: ExecutionControl,
    timeout: ExecutionControl = ExecutionControl.Never,
    exhausted: () -> Boolean,
    declined: () -> Boolean = { false },
    pendingIncumbentTrial: () -> Boolean = { false },
    open: () -> ExactNeighborhood,
): PortfolioWorker {
    fun stop(global: ExecutionControl) = lifetime or global or ExecutionControl(exhausted)
    fun slice(global: ExecutionControl, millis: Long) = stop(global) or
        if (millis == Long.MAX_VALUE) ExecutionControl.Never else ExecutionControl.after(millis.milliseconds)
    fun reason(global: ExecutionControl) = if (timeout()) {
        TerminationReason.Timeout
    } else if ((lifetime or global)()) {
        TerminationReason.Cancelled
    } else if (declined()) {
        TerminationReason.RepresentationDeclined
    } else {
        TerminationReason.BudgetExhausted
    }
    if (objective == null) {
        return PortfolioWorker.ofSolve(
            label,
            armId,
            countsInstructions = true,
            resumable = {
                object : InstructionSlicedSolve, ReseedGuard {
                    override val canReseed: Boolean get() = false
                    private val search = open()
                    private var closed = false
                    private var result: SolveResult? = null
                    override val isDone: Boolean get() = result != null
                    override val stats get() = search.stats
                    override val work get() = search.work
                    override fun runSlice(global: ExecutionControl, sliceMillis: Long, sliceNodes: Long): SolveResult? =
                        runInstructionSlice(global, sliceMillis, sliceNodes)
                    override fun runInstructionSlice(
                        global: ExecutionControl,
                        sliceMillis: Long,
                        sliceInstructions: Long,
                    ): SolveResult? {
                        result?.let { return it }
                        check(!closed)
                        val done = search.advance(
                            slice(global, sliceMillis),
                            if (sliceInstructions < 0) Long.MAX_VALUE else sliceInstructions,
                        )
                        if (done || stop(global)()) result = search.solveResult(reason(global))
                        return result
                    }
                    override fun close() {
                        if (closed) return
                        closed = true
                        search.close()
                    }
                }
            },
            solve = { global, budget ->
                open().use { search ->
                    search.advance(stop(global), budget ?: Long.MAX_VALUE)
                    search.solveResult(reason(global))
                }
            },
        ).also { if (label.startsWith("alns")) it.family = ArmFamily.Lns }
    }
    return PortfolioWorker.ofMinimize(
        label,
        armId,
        countsInstructions = true,
        resumable = {
            object : InstructionSlicedSearch, ReseedGuard {
                override val canReseed: Boolean get() = false
                override val incumbentTrialPending: Boolean get() = pendingIncumbentTrial()
                private val search = open()
                private var closed = false
                private var result: MinimizeResult? = null
                override val isDone: Boolean get() = result != null
                override val stats get() = search.stats
                override val work get() = search.work
                override val chargedInstructions get() = search.work
                override fun runSlice(
                    global: ExecutionControl,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? = runInstructionSlice(global, sliceMillis, sliceNodes, onIncumbent)
                override fun runInstructionSlice(
                    global: ExecutionControl,
                    sliceMillis: Long,
                    sliceInstructions: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    result?.let { return it }
                    check(!closed)
                    val done = search.advance(
                        slice(global, sliceMillis),
                        if (sliceInstructions < 0) Long.MAX_VALUE else sliceInstructions,
                    ) { sample, value ->
                        onIncumbent(
                            MinimizeResult.BestFound(
                            sample,
                            requireNotNull(value).toDouble(),
                            TerminationReason.BudgetExhausted,
                            search.stats,
                        )
                        )
                    }
                    if (done || stop(global)()) result = search.minimizeResult(reason(global))
                    return result
                }
                override fun close() {
                    if (closed) return
                    closed = true
                    search.close()
                }
            }
        },
        improvements = { _, _, global, budget ->
            open().use { search ->
                search.advance(stop(global), budget ?: Long.MAX_VALUE)
                sequenceOf(search.minimizeResult(reason(global)))
            }
        },
    ).also { if (label.startsWith("alns")) it.family = ArmFamily.Lns }
}
