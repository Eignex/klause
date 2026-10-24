package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.engine.reconstructRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.magnitudeBitLength

internal data class LinearPrimalRecoveryLimits(
    val work: Long = 2_000_000L,
    val allocation: Long = 64L * 1024L * 1024L,
    val entries: Long = 65_536L,
)

// Recovered coordinates remain proposals; the original-source checker owns witness admission.
internal fun recoverLinearPrimal(
    coordinates: List<SourceCoordinate>,
    point: List<BigFraction>,
    rows: List<ExactRationalInequality>,
    inactive: Set<Int>,
    cancellation: Cancellation,
    complete: (List<ExactRationalInequality>, Int, Cancellation) -> List<BigFraction>?,
    observe: (String, Long) -> Unit,
    limits: LinearPrimalRecoveryLimits = LinearPrimalRecoveryLimits(),
): List<BigFraction>? {
    val meter = LinearPrimalMeter(limits, cancellation)
    observe("PrimalRecoveryAttempts", 1L)
    return try {
        meter.reserve(coordinates.size.toLong() + rows.size, 64L * coordinates.size + 32L * rows.size)
        if (coordinates.size != point.size) return null
        for (id in coordinates.indices) {
            meter.number(point[id])
            if (coordinates[id].kind != SourceCoordinate.Kind.Real && point[id].den != BIG_ONE) {
                observe("PrimalRecoveryFractional", 1L)
                return null
            }
        }
        val active = ArrayList<ExactRationalInequality>()
        var entries = 0L
        for (id in rows.indices) {
            meter.reserve(1L)
            if (id in inactive) continue
            val row = rows[id]
            entries += row.columns.size + 1L
            if (entries > limits.entries) throw LinearPrimalStop("Entries")
            meter.number(row.rhs)
            for (entry in row.columns.indices) {
                if (row.columns[entry] !in point.indices) return null
                meter.number(row.coefficients[entry])
            }
            active += row
        }
        val reconstructed = point.mapIndexed { id, value ->
            meter.reserve(1L, 32L)
            if (coordinates[id].kind != SourceCoordinate.Kind.Real) value else {
                val part = reconstructRational(value.toDouble(), onStep = { meter.reserve(1L) })
                part?.let { meter.number(BigFraction.of(bigIntOf(it.numerator), bigIntOf(it.denominator))) }
                    ?: value
            }
        }
        if (meter.satisfies(reconstructed, active)) {
            observe("PrimalRecoveryReconstructed", 1L)
            return reconstructed
        }
        val residual = ArrayList<ExactRationalInequality>()
        val incidence = List(point.size) { ArrayList<Int>() }
        for (row in active) {
            meter.reserve(row.columns.size.toLong() + 1L, 64L + 64L * row.columns.size)
            var rhs = row.rhs
            val columns = ArrayList<Int>()
            val coefficients = ArrayList<BigFraction>()
            for (entry in row.columns.indices) {
                val column = row.columns[entry]
                val coefficient = row.coefficients[entry]
                if (coefficient.isZero) continue
                if (coordinates[column].kind == SourceCoordinate.Kind.Real) {
                    columns += column
                    coefficients += coefficient
                } else {
                    rhs = meter.subtractProduct(rhs, coefficient, point[column])
                }
            }
            if (columns.isEmpty()) {
                if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) {
                    observe("PrimalRecoveryDiscreteRows", 1L)
                    return null
                }
            } else {
                for (column in columns) incidence[column] += residual.size
                residual += ExactRationalInequality(columns.toIntArray(), coefficients, rhs, row.strict)
            }
        }
        val recovered = reconstructed.toMutableList()
        val seen = BooleanArray(point.size)
        val selected = BooleanArray(residual.size)
        val local = IntArray(point.size) { -1 }
        for (root in point.indices) {
            meter.reserve(1L)
            if (seen[root] || incidence[root].isEmpty()) continue
            val members = arrayListOf(root)
            val componentRows = ArrayList<Int>()
            seen[root] = true
            var next = 0
            while (next < members.size) {
                val column = members[next++]
                meter.reserve(1L, 16L)
                for (id in incidence[column]) {
                    meter.reserve(1L)
                    if (selected[id]) continue
                    selected[id] = true
                    componentRows += id
                    for (neighbor in residual[id].columns) {
                        meter.reserve(1L)
                        if (!seen[neighbor]) {
                            seen[neighbor] = true
                            members += neighbor
                        }
                    }
                }
            }
            for (index in members.indices) local[members[index]] = index
            val localRows = componentRows.map { id ->
                val row = residual[id]
                meter.reserve(row.columns.size.toLong() + 1L, 64L + 32L * row.columns.size)
                ExactRationalInequality(row.columns.map { local[it] }.toIntArray(), row.coefficients,
                    row.rhs, row.strict)
            }
            if (meter.satisfies(members.map { recovered[it] }, localRows)) continue
            observe("PrimalRecoveryComponents", 1L)
            val values = complete(localRows, members.size, cancellation)
            meter.reserve(1L)
            if (values == null || values.size != members.size) {
                observe("PrimalRecoveryComponentUnavailable", 1L)
                return null
            }
            for (index in members.indices) recovered[members[index]] = meter.number(values[index])
        }
        if (meter.satisfies(recovered, active)) {
            observe("PrimalRecoveryResidual", 1L)
            recovered
        } else null
    } catch (stop: LinearPrimalStop) {
        observe("PrimalRecovery${stop.reason}", 1L)
        null
    } finally {
        observe("PrimalRecoveryWork", meter.work)
        observe("PrimalRecoveryAllocation", meter.allocation)
    }
}

private class LinearPrimalStop(val reason: String) : RuntimeException()

private class LinearPrimalMeter(private val limits: LinearPrimalRecoveryLimits, private val token: Cancellation) {
    var work = 0L
        private set
    var allocation = 0L
        private set

    fun reserve(units: Long, bytes: Long = 0L) {
        if (token()) throw LinearPrimalStop("Stopped")
        if (units > limits.work - work) throw LinearPrimalStop("WorkLimit")
        if (bytes > limits.allocation - allocation) throw LinearPrimalStop("AllocationLimit")
        work += units
        allocation += bytes
        token.charge(units)
        if (token()) throw LinearPrimalStop("Stopped")
    }

    fun number(value: BigFraction): BigFraction {
        val bits = maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength())
        reserve(1L + bits / 64L, 32L + bits / 4L)
        if (bits > 4096) throw LinearPrimalStop("ScalarLimit")
        return value
    }

    private fun arithmetic(left: BigFraction, right: BigFraction) {
        val bits = maxOf(left.num.magnitudeBitLength(), left.den.magnitudeBitLength()).toLong() +
            maxOf(right.num.magnitudeBitLength(), right.den.magnitudeBitLength()) + 2L
        val words = (bits + 63L) / 64L
        reserve(words * words + 1L, 64L + bits / 2L)
    }

    fun subtractProduct(value: BigFraction, left: BigFraction, right: BigFraction): BigFraction {
        arithmetic(left, right)
        val product = number(left * right)
        arithmetic(value, product)
        return number(value - product)
    }

    fun satisfies(point: List<BigFraction>, rows: List<ExactRationalInequality>): Boolean {
        for (row in rows) {
            var rhs = row.rhs
            for (entry in row.columns.indices) {
                rhs = subtractProduct(rhs, row.coefficients[entry], point[row.columns[entry]])
            }
            if (if (row.strict) rhs <= BigFraction.ZERO else rhs < BigFraction.ZERO) return false
        }
        reserve(1L)
        return true
    }
}
