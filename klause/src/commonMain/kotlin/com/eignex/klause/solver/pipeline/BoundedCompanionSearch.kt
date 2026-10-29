package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class BoundedCompanionSearch(
    private val search: ResumableSearch,
    private val prefix: String,
    private val activeLimit: Duration = 4_000.milliseconds,
    private val sliceLimit: Duration = 250.milliseconds,
    private val clock: TimeSource = TimeSource.Monotonic,
) : InstructionSlicedSearch, ReseedGuard, ResumableSearch by search {
    private var active = Duration.ZERO
    private var terminal: MinimizeResult? = null
    private var retired = false
    override val canReseed: Boolean get() = false
    override val incumbentTrialPending: Boolean get() =
        !isDone && (search as? ReseedGuard)?.incumbentTrialPending == true
    override val isDone: Boolean get() = terminal != null
    override val chargedInstructions: Long get() =
        (search as? InstructionSlicedSearch)?.chargedInstructions ?: search.work
    override val stats get() = search.stats.let { original ->
        val stages = mapOf(
            "${prefix}ActiveMs" to active.inWholeMilliseconds,
            "${prefix}Retired" to if (retired) 1L else 0L,
            "${prefix}Optimal" to if (terminal is MinimizeResult.Optimal) 1L else 0L,
            "${prefix}Refuted" to if (terminal is MinimizeResult.Infeasible) 1L else 0L,
        )
        original.copy(smt = original.smt.copy(
            completeSearchStages = original.smt.completeSearchStages.orEmpty() + stages))
    }

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
        advance(sliceMillis) { millis -> search.runSlice(global, millis, boundedWork(sliceNodes), onIncumbent) }

    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = advance(sliceMillis) { millis ->
        val instructions = search as? InstructionSlicedSearch
        if (instructions == null) search.runSlice(global, millis, boundedWork(sliceInstructions), onIncumbent)
        else instructions.runInstructionSlice(global, millis, boundedWork(sliceInstructions), onIncumbent)
    }

    private fun advance(sliceMillis: Long, run: (Long) -> MinimizeResult?): MinimizeResult? {
        terminal?.let { return it }
        if (active >= activeLimit) {
            retired = true
            return MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats).also { terminal = it }
        }
        val mark = clock.markNow()
        try {
            terminal = run(minOf(sliceMillis, sliceLimit.inWholeMilliseconds,
                (activeLimit - active).inWholeMilliseconds.coerceAtLeast(1)))
        } finally {
            active += mark.elapsedNow()
        }
        return terminal
    }

    private fun boundedWork(work: Long): Long = if (work < 0) 5_000L else minOf(work, 5_000L)
}
