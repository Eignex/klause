package com.eignex.klause.lp

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal data class SparseSourceLpLimits(val work: Long, val allocation: Long, val scalarBits: Int = 4096) {
    init {
        require(work >= 0L && allocation >= 0L && scalarBits > 0)
    }
}

internal enum class SparseSourceLpDecline { CANCELLED, SHAPE, SCALAR_WIDTH, WORK, ALLOCATION }

internal data class SparseSourceLpMetrics(
    val inputEntries: Long,
    val emittedEntries: Long,
    val scalarBits: Long,
    val work: Long,
    val allocation: Long,
)

internal data class SparseSourceLpPreparation(
    val prepared: SparseSourceLp?,
    val decline: SparseSourceLpDecline?,
    val metrics: SparseSourceLpMetrics,
)

// Preparation admits sparse authority, independently of exact-primal reconstruction and basis fill.
internal class SparseSourceLp private constructor(
    val model: ExactLpModel,
    private val rowSupport: List<IntArray>,
    private val columnSupport: List<IntArray>,
) {
    fun rowColumns(row: Int): IntArray = rowSupport[row].copyOf()
    fun columnRows(column: Int): IntArray = columnSupport[column].copyOf()

    companion object {
        fun prepare(
            rows: List<ExactRationalInequality>,
            columns: List<ExactLpColumn>,
            costs: List<BigFraction>,
            constant: BigFraction,
            limits: SparseSourceLpLimits,
            cancellation: Cancellation = Cancellation.Never,
        ): SparseSourceLpPreparation {
            val meter = SparsePreparationMeter(limits, cancellation)
            var inputEntries = 0L
            var emittedEntries = 0L
            fun result(prepared: SparseSourceLp? = null) = SparseSourceLpPreparation(
                prepared, meter.decline,
                SparseSourceLpMetrics(inputEntries, emittedEntries, meter.bits, meter.work, meter.allocation),
            )
            if (!meter.reserve(1L, 0L)) return result()
            if (columns.size != costs.size || columns.size.toLong() + rows.size > Int.MAX_VALUE) {
                meter.decline = SparseSourceLpDecline.SHAPE
                return result()
            }
            // Includes the owned CSC, adjacency, vector and constructor-copy reservations. Exact payload is
            // reserved per scalar below; this is modeled preparation storage, not measured JVM allocation.
            val dimension = columns.size.toLong() + rows.size
            if (!meter.reserve(dimension * 8L + 1L, dimension * 256L + 1024L) ||
                !meter.number(constant)
            ) return result()
            for (column in columns.indices) {
                val descriptor = columns[column]
                if (!meter.number(costs[column]) || !meter.number(descriptor.origin.value) ||
                    descriptor.bounds.lower?.let { !meter.side(it) } == true ||
                    descriptor.bounds.upper?.let { !meter.side(it) } == true
                ) return result()
            }
            for (row in rows) {
                if (!meter.number(row.rhs)) return result()
                for (entry in row.columns.indices) {
                    if (!meter.reserve(1L, 0L)) return result()
                    inputEntries++
                    if (row.columns[entry] !in columns.indices) {
                        meter.decline = SparseSourceLpDecline.SHAPE
                        return result()
                    }
                    val value = row.coefficients[entry]
                    if (!meter.number(value)) return result()
                    if (!value.isZero) {
                        if (!meter.reserve(4L, 192L)) return result()
                        emittedEntries++
                    }
                }
            }
            val matrix = List(columns.size) { ArrayList<ExactLpEntry>() }
            val rowSupport = ArrayList<IntArray>(rows.size)
            val rhs = ArrayList<ExactLpNumber>(rows.size)
            val zero = ExactLpNumber.of(0L)
            for (id in rows.indices) {
                if (!meter.poll()) return result()
                val row = rows[id]
                val support = ArrayList<Int>()
                for (entry in row.columns.indices) {
                    if (!meter.poll()) return result()
                    val coefficient = row.coefficients[entry]
                    if (coefficient.isZero) continue
                    val column = row.columns[entry]
                    matrix[column].add(ExactLpEntry(id, ExactLpNumber.of(coefficient)))
                    support += column
                }
                rowSupport += support.toIntArray()
                rhs += ExactLpNumber.of(row.rhs)
            }
            val model = ExactLpModel(
                matrix, rhs,
                columns + rows.map {
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero, it.strict)), integral = false)
                },
                rows.map { ExactLpRow(strict = it.strict) },
                ExactLpObjective(costs.map(ExactLpNumber::of) + List(rows.size) { zero }, ExactLpNumber.of(constant)),
            )
            if (!meter.poll()) return result()
            val adjacency = matrix.map { column ->
                column.map { it.row }.toIntArray()
            }
            if (!meter.poll()) return result()
            return result(SparseSourceLp(model, rowSupport, adjacency))
        }
    }
}

private class SparsePreparationMeter(private val limits: SparseSourceLpLimits, private val cancellation: Cancellation) {
    var decline: SparseSourceLpDecline? = null
    var work = 0L
        private set
    var allocation = 0L
        private set
    var bits = 0L
        private set

    fun poll(): Boolean {
        if (decline == null && cancellation()) decline = SparseSourceLpDecline.CANCELLED
        return decline == null
    }

    fun reserve(operations: Long, bytes: Long): Boolean {
        if (!poll()) return false
        decline = when {
            operations > limits.work - work -> SparseSourceLpDecline.WORK
            bytes > limits.allocation - allocation -> SparseSourceLpDecline.ALLOCATION
            else -> null
        }
        if (decline != null) return false
        work += operations
        allocation += bytes
        return true
    }

    fun number(value: BigFraction): Boolean {
        if (!reserve(1L, 0L)) return false
        val numerator = value.num.magnitudeBitLength()
        val denominator = value.den.magnitudeBitLength()
        if (maxOf(numerator, denominator) > limits.scalarBits) {
            decline = SparseSourceLpDecline.SCALAR_WIDTH
            return false
        }
        val width = numerator.toLong() + denominator
        if (!reserve((width + 63L) / 64L, (width + 7L) / 8L * 4L)) return false
        bits += width
        return true
    }

    fun side(side: ExactLpSide): Boolean {
        if (!number(side.number.value)) return false
        val premises = side.premises ?: return true
        if (!reserve(premises.size, premises.size * 32L)) return false
        return premises.boundEntries().all { number(it.threshold.value) }
    }
}
