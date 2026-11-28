package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ContinuationBudget
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStatus
import com.eignex.klause.simplex.exact.ContinuationStop
import com.eignex.klause.simplex.exact.ExactContinuationInput
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.saturatedAdd
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

internal sealed interface LpExactOptimization {
    class Optimum(val point: List<BigFraction>) : LpExactOptimization
    class Infimum(val endpoint: List<BigFraction>) : LpExactOptimization
    class Unbounded(val direction: List<BigFraction>) : LpExactOptimization
    class Declined(val reason: ContinuationDecline) : LpExactOptimization
}

// Optimizes the continuous region containing the caller's checked point. The point also supplies a feasible
// base for any verified recession direction. The caller checks the selected cell in its source coordinates.
internal suspend fun optimizeFeasibleExactLp(
    source: ExactLpModel,
    limits: ExactContinuationLimits,
    cancellation: ExecutionControl,
    policy: LpCertificationPolicy,
    observe: (work: Long, allocation: Long, elapsedNs: Long, pivots: Int) -> Unit,
    remainingWork: () -> Long = { Long.MAX_VALUE },
    checkpoint: suspend () -> Unit,
): LpExactOptimization = try {
    ExactOptimizationRun(source, limits, cancellation, policy, observe, remainingWork, checkpoint).solve().let {
        if (cancellation()) LpExactOptimization.Declined(ContinuationDecline.CANCELLED) else it
    }
} catch (stopped: ContinuationStop) {
    LpExactOptimization.Declined(stopped.reason)
}

private const val OPTIMIZATION_PIVOT_QUANTUM = 20

private class ExactOptimizationRun(
    private val source: ExactLpModel,
    private val limits: ExactContinuationLimits,
    private val cancellation: ExecutionControl,
    private val policy: LpCertificationPolicy,
    private val observe: (Long, Long, Long, Int) -> Unit,
    private val remainingWork: () -> Long,
    private val checkpoint: suspend () -> Unit,
) {
    private val cache = LpExactContinuationCache()
    private var pivots = 0

    suspend fun solve(): LpExactOptimization {
        val strict = metered { budget ->
            (0 until source.numVars).any {
                budget.step()
                val bounds = source.effectiveBounds(it)
                bounds.lower?.strict == true || bounds.upper?.strict == true
            }
        }
        val open = feasible { budget -> primalDual(strict, budget) }
        open?.let { return LpExactOptimization.Optimum(originalPoint(it)) }
        if (strict) {
            val closure = feasible { budget -> primalDual(false, budget) }
            closure?.let { return LpExactOptimization.Infimum(originalPoint(it)) }
        }
        val ray = feasible(::recession) ?: throw ContinuationStop(ContinuationDecline.CANDIDATE)
        val direction = metered { budget ->
            budget.step(source.n.toLong(), source.n * 8L)
            ray.take(source.n)
        }
        return LpExactOptimization.Unbounded(direction)
    }

    private suspend fun feasible(build: (ContinuationBudget) -> ExactLpModel): List<BigFraction>? {
        cache.invalidate()
        val state = metered { LpExactState(build(it)) }
        val input = metered { capture(state.model, it) }
        val session = cache.initialize(input)
        val pivotLimit = limits.maxPivots - pivots
        var offered = minOf(pivotLimit, OPTIMIZATION_PIVOT_QUANTUM)
        while (true) {
            val result = session.resume(liveLimits().copy(maxPivots = offered), cancellation)
            val measured = result.metrics
            pivots += measured.pivots
            observe(measured.work, measured.allocation, measured.elapsedNs, measured.pivots)
            checkpoint()
            val decline = measured.decline
            if (decline == ContinuationDecline.PIVOTS && offered < pivotLimit) {
                offered += minOf(OPTIMIZATION_PIVOT_QUANTUM, pivotLimit - offered)
                continue
            }
            if (decline != null) throw ContinuationStop(decline)
            return verify(state, result)
        }
    }

    private fun verify(state: LpExactState, result: ExactContinuationResult): List<BigFraction>? {
        val point = result.values?.let { values ->
            metered { budget ->
                budget.step(state.model.n.toLong(), state.model.n * 8L)
                values.take(state.model.n)
            }
        }
        val remaining = cache.remaining(liveLimits())
        val started = TimeSource.Monotonic.markNow()
        val deadline = remaining.maxTimeNs?.let { ExecutionControl.until(started + it.nanoseconds) }
        val stop = deadline?.let { cancellation or it } ?: cancellation
        val checked = verifyRationalCertificate(
            state, point, result.ray, stop,
            ReconstructionLimits(
                maxCoordinates = remaining.maxColumns,
                maxEntries = remaining.maxCells,
                maxWork = remaining.maxWork,
                maxAllocation = remaining.maxAllocation,
                maxBits = remaining.maxBits,
            ),
        )
        val elapsed = started.elapsedNow().inWholeNanoseconds
        val cost = checked.metrics
        cache.accountInput(cost.work, cost.allocation, elapsed)
        observe(cost.work, cost.allocation, elapsed, 0)
        val interrupted = when {
            cancellation() -> ContinuationDecline.CANCELLED
            deadline?.invoke() == true -> ContinuationDecline.TIME
            else -> null
        }
        if (interrupted != null) throw ContinuationStop(interrupted)
        if (policy.acceptBoolean(LpCertifier.RATIONAL, checked.witness != null || checked.conflict != null)) {
            val witness = policy.acceptNullable(LpCertifier.EXACT_BASIS, checked.witness)
            if (witness != null) return witness.primal
            if (policy.acceptNullable(LpCertifier.EXACT_FARKAS, checked.conflict) != null) return null
        }
        throw ContinuationStop(cost.decline?.continuationDecline() ?: ContinuationDecline.CANDIDATE)
    }

    private fun capture(model: ExactLpModel, budget: ContinuationBudget): ExactContinuationInput {
        budget.step(model.numVars.toLong() + model.m, model.numVars * 96L + model.m * 64L)
        val bounds = List(model.numVars, model::effectiveBounds)
        return ExactContinuationInput(
            List(model.n) { column ->
                val entries = model.columnEntries(column)
                budget.step(entries.size.toLong(), entries.size * 48L)
                entries.map { it.row to budget.fraction(it.number.value) }
            },
            List(model.m) { model.rhs(it).value },
            bounds.map { it.lower?.number?.value },
            bounds.map { it.upper?.number?.value },
            List(model.m) { model.n + it },
            bounds.mapIndexed { j, sides ->
                budget.step()
                when {
                    j >= model.n -> ContinuationStatus.BASIC
                    sides.fixed -> ContinuationStatus.FIXED
                    sides.lower != null -> ContinuationStatus.LOWER
                    sides.upper != null -> ContinuationStatus.UPPER
                    else -> ContinuationStatus.FREE
                }
            },
            bounds.map { it.lower?.strict == true },
            bounds.map { it.upper?.strict == true },
        )
    }

    // For Mx = b with bounded x, M^T y + p - q = c and c^T x = b^T y + l^T p - u^T q.
    // Only finite sides get multipliers. Equality of the two objectives certifies optimality by weak duality.
    private fun primalDual(strict: Boolean, budget: ContinuationBudget): ExactLpModel {
        var sides = 0L
        for (j in 0 until source.numVars) {
            budget.step()
            val bounds = source.effectiveBounds(j)
            if (bounds.lower != null) sides++
            if (bounds.upper != null) sides++
        }
        val rows = source.m.toLong() + source.numVars + 1L
        val frame = OptimizationFrame(rows, rows - 1L + sides, budget)
        val gap = frame.rows - 1
        val dualColumns = List(source.m) { ArrayList<ExactLpEntry>() }
        for (j in 0 until source.numVars) {
            frame.column(source.effectiveBounds(j).withStrictness(strict)) { column ->
                sourceEntries(j) { row, number ->
                    frame.entry(column, row, number)
                    frame.entry(dualColumns[row], source.m + j, number)
                }
                frame.entry(column, gap, source.objective.cost(j))
            }
        }
        for ((i, column) in dualColumns.withIndex()) {
            frame.column(ExactLpBounds()) {
                budget.step(column.size.toLong(), column.size * 8L)
                it.addAll(column)
                frame.entry(it, gap, source.rhs(i), negate = true)
            }
        }
        for (j in 0 until source.numVars) {
            val bounds = source.effectiveBounds(j)
            bounds.lower?.let { lower ->
                frame.column(NONNEGATIVE) {
                    frame.entry(it, source.m + j, ONE)
                    frame.entry(it, gap, lower.number, negate = true)
                }
            }
            bounds.upper?.let { upper ->
                frame.column(NONNEGATIVE) {
                    frame.entry(it, source.m + j, MINUS_ONE)
                    frame.entry(it, gap, upper.number)
                }
            }
        }
        return frame.build { row ->
            when {
                row < source.m -> source.rhs(row)
                row < gap -> source.objective.cost(row - source.m)
                else -> ZERO
            }
        }
    }

    private fun recession(budget: ContinuationBudget): ExactLpModel {
        val frame = OptimizationFrame(source.m + 1L, source.numVars.toLong(), budget)
        for (j in 0 until source.numVars) {
            val bounds = source.effectiveBounds(j)
            frame.column(ExactLpBounds(
                ZERO_SIDE.takeIf { bounds.lower != null },
                ZERO_SIDE.takeIf { bounds.upper != null },
            )) { column ->
                sourceEntries(j) { row, number -> frame.entry(column, row, number) }
                frame.entry(column, source.m, source.objective.cost(j))
            }
        }
        return frame.build(lastSlack = NONNEGATIVE) { row -> if (row == source.m) MINUS_ONE else ZERO }
    }

    private inline fun sourceEntries(column: Int, visit: (Int, ExactLpNumber) -> Unit) {
        if (column < source.n) {
            for (entry in source.columnEntries(column)) visit(entry.row, entry.number)
        } else {
            visit(column - source.n, ONE)
        }
    }

    private fun originalPoint(point: List<BigFraction>): List<BigFraction> = metered { budget ->
        budget.step(source.n.toLong(), source.n * 8L)
        List(source.n) { j ->
            val origin = source.column(j).origin.value
            budget.preflight(point[j], origin)
            budget.fraction(point[j] + origin)
        }
    }

    private fun liveLimits() = limits.copy(
        maxWork = minOf(limits.maxWork, saturatedAdd(cache.usedWork, remainingWork())),
    )

    private inline fun <T> metered(block: (ContinuationBudget) -> T): T {
        val budget = ContinuationBudget(cache.remaining(liveLimits()), cancellation)
        try {
            budget.checkpoint()
            return block(budget).also { budget.checkpoint() }
        } finally {
            val elapsed = budget.elapsedNs
            cache.accountInput(budget.work, budget.allocation, elapsed)
            observe(budget.work, budget.allocation, elapsed, 0)
        }
    }
}

private val ZERO = ExactLpNumber.of(0L)
private val ONE = ExactLpNumber.of(1L)
private val MINUS_ONE = ExactLpNumber.of(-1L)
private val ZERO_SIDE = ExactLpSide(ZERO)
private val NONNEGATIVE = ExactLpBounds(ZERO_SIDE)
private val FIXED_ZERO = ExactLpBounds(ZERO_SIDE, ZERO_SIDE)

private fun ExactLpBounds.withStrictness(strict: Boolean) = if (strict) this else
    ExactLpBounds(lower?.copy(strict = false), upper?.copy(strict = false))

private class OptimizationFrame(rows: Long, structural: Long, private val budget: ContinuationBudget) {
    val rows: Int
    private val matrix: ArrayList<List<ExactLpEntry>>
    private val columns: ArrayList<ExactLpColumn>

    init {
        val total = rows + structural
        if (total >= Int.MAX_VALUE || !budget.limits.admitsDimensions(rows.toInt(), total.toInt())) {
            throw ContinuationStop(ContinuationDecline.DIMENSION)
        }
        val tableauBytes = rows * (total + 1L) * 8L
        if (tableauBytes > budget.limits.maxAllocation - budget.allocation) {
            throw ContinuationStop(ContinuationDecline.ALLOCATION)
        }
        budget.step(total + rows, total * 192L + rows * 96L)
        this.rows = rows.toInt()
        matrix = ArrayList(structural.toInt())
        columns = ArrayList(total.toInt())
    }

    fun column(bounds: ExactLpBounds, build: (ArrayList<ExactLpEntry>) -> Unit) {
        budget.step()
        bounds.lower?.let { budget.fraction(it.number.value) }
        bounds.upper?.let { budget.fraction(it.number.value) }
        columns += ExactLpColumn(bounds, integral = false)
        matrix += ArrayList<ExactLpEntry>().also(build)
    }

    fun entry(target: MutableList<ExactLpEntry>, row: Int, number: ExactLpNumber, negate: Boolean = false) {
        budget.step()
        if (number.value.isZero) return
        budget.fraction(number.value)
        budget.step(bytes = 64L)
        target += ExactLpEntry(row, if (negate) ExactLpNumber.of(number.value.negated()) else number)
    }

    fun build(lastSlack: ExactLpBounds = FIXED_ZERO, rhs: (Int) -> ExactLpNumber): ExactLpModel {
        repeat(rows) { i ->
            budget.step()
            columns += ExactLpColumn(if (i == rows - 1) lastSlack else FIXED_ZERO, integral = false)
        }
        return ExactLpModel(matrix, List(rows) { rhs(it).also { number -> budget.fraction(number.value) } }, columns,
            List(rows) { ExactLpRow() }, ExactLpObjective(List(columns.size) { ZERO }))
    }
}
