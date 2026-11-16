package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

internal class QuadraticWorkerRuntime(
    private val native: QuadraticNativeScope,
    private val sense: ObjectiveSense,
    private val snapshot: () -> SolveStats,
    private val partial: () -> QuadraticOptimizationResult,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    var terminal: QuadraticOptimizationResult? = null
    var interrupted = false
        private set
    private var closed = false
    private var publication: ((QuadraticIncumbent) -> Unit)? = null
    private var neighborhoodPreparationInstructions = 0L

    fun publish(incumbent: QuadraticIncumbent) { publication?.invoke(incumbent) }

    fun execute(body: () -> QuadraticOptimizationResult): QuadraticOptimizationResult = try {
        guarded(body, partial)
    } finally {
        close()
    }

    private fun <T> guarded(body: () -> T, stopped: () -> T): T = try {
        body()
    } catch (_: PolynomialInterrupted) {
        stopped()
    } catch (stop: PolynomialResourceLimit) {
        native.decline(stop.resource)
        stopped()
    } catch (_: CancellationException) {
        interrupted = true
        stopped()
    }

    private fun run(onIncumbent: (MinimizeResult.WithSample) -> Unit, advance: () -> Unit): MinimizeResult? {
        terminal?.let { return scalar(it) }
        check(!closed) { "quadratic worker is closed" }
        publication = { onIncumbent(quadraticScalar(it, sense, snapshot())) }
        try {
            terminal = guarded({ advance(); terminal }, partial)
        } finally {
            publication = null
        }
        return terminal?.let(::scalar)
    }

    private fun scalar(result: QuadraticOptimizationResult): MinimizeResult =
        (result as? QuadraticOptimizationResult.WithIncumbent)?.let {
            quadraticScalar(it.incumbent, sense, snapshot())
        } ?: MinimizeResult.Unknown(TerminationReason.BudgetExhausted, snapshot())

    fun completeHandle(countedWork: () -> Long, advance: (ExecutionControl, Long, Long) ->
        Unit): QuadraticPortfolioSearch =
        object : QuadraticPortfolioSearch, ReseedGuard {
            override val canReseed: Boolean get() = false
            override val completion: QuadraticOptimizationResult? get() = terminal
            override val isDone: Boolean get() = terminal != null
            override val stats: SolveStats get() = snapshot()
            override val work: Long get() = countedWork()
            override fun runSlice(global: ExecutionControl, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = run(onIncumbent) {
                advance(global, sliceMillis, sliceNodes)
            }
            override fun close() = this@QuadraticWorkerRuntime.close()
        }

    fun neighborhoodHandle(
        cancellation: ExecutionControl,
        retained: () -> QuadraticNeighborhoodCursor?,
        prepare: () -> QuadraticNeighborhoodCursor,
    ): QuadraticPortfolioSearch = object : QuadraticPortfolioSearch, InstructionSlicedSearch, ReseedGuard {
        override val canReseed: Boolean get() = false
        override val completion: QuadraticOptimizationResult? get() = terminal
        override val isDone: Boolean get() = terminal != null
        override val stats: SolveStats get() = snapshot()
        override val work: Long get() = neighborhoodPreparationInstructions + minOf(retained()?.work ?: 0L,
            Long.MAX_VALUE - neighborhoodPreparationInstructions)
        override val chargedInstructions: Long get() = work
        override fun runSlice(global: ExecutionControl, sliceMillis: Long, sliceNodes: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
            runInstructionSlice(global, sliceMillis, sliceNodes, onIncumbent)
        override fun runInstructionSlice(global: ExecutionControl, sliceMillis: Long, sliceInstructions: Long,
            onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = run(onIncumbent) {
            val cursor = retained() ?: kotlin.run {
                val before = native.work
                try {
                    prepare()
                } finally {
                    neighborhoodPreparationInstructions += minOf(
                        quadraticNativeInstructions((native.work - before).coerceAtLeast(0)),
                        Long.MAX_VALUE - neighborhoodPreparationInstructions,
                    )
                }
            }
            val stop = native.polynomial or cancellation or global
            val segment = stop or if (sliceMillis == Long.MAX_VALUE) ExecutionControl.Never
                else ExecutionControl.after(sliceMillis.milliseconds)
            val done = cursor.advance(segment,
                if (sliceInstructions < 0) Long.MAX_VALUE else sliceInstructions, global)
            if (done || stop()) terminal = partial()
        }
        override fun close() = this@QuadraticWorkerRuntime.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        cleanup()
    }
}
