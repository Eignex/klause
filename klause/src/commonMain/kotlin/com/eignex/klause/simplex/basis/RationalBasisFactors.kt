package com.eignex.klause.simplex.basis

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.Frac128
import com.eignex.klause.simplex.exact.Frac128Ops
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Orders map pivot positions to source rows/columns. Factory calls copy caller-owned arrays.
internal class RationalBasisOrder(val rows: IntArray, val columns: IntArray)

internal data class RationalBasisLimits(
    val dimension: Int = 128,
    val work: Long = 100_000_000,
    val allocationBytes: Long = 256L * 1024 * 1024,
    val fill: Int = 16384,
    val bits: Int = 4096,
    val time: Duration = 5.seconds,
) {
    init {
        require(dimension >= 0 && work >= 0 && allocationBytes >= 0 && fill >= 0 && bits >= 0)
        require(time >= Duration.ZERO)
    }
}

internal enum class RationalBasisDecline { DIMENSION, WORK, MEMORY, FILL, BITS, TIME, CANCELLED }

// Allocation is cumulative modeled reservation, not measured bytes or a process RSS guarantee.
// Work counts visits plus ceil(bitBound/64)^2 per arithmetic operation/conversion.
internal data class RationalBasisStats(
    val work: Long,
    val allocationBytes: Long,
    val peakFill: Int,
    val maxBits: Int,
    val maxIntermediateBits: Long,
    val builds: Int,
    val proposedAttempts: Int,
    val fallbacks: Int,
    val restarts: Int,
)

internal sealed interface RationalBasisBuild {
    val stats: RationalBasisStats
    data class Ready(val factors: RationalBasisFactors, override val stats: RationalBasisStats) : RationalBasisBuild
    data class Singular(val rank: Int, override val stats: RationalBasisStats) : RationalBasisBuild
    data class Declined(val reason: RationalBasisDecline, override val stats: RationalBasisStats) : RationalBasisBuild
}

internal sealed interface RationalBasisSolve {
    val stats: RationalBasisStats
    data class Solved(val values: List<BigFraction>, override val stats: RationalBasisStats) : RationalBasisSolve
    data class Declined(val reason: RationalBasisDecline, override val stats: RationalBasisStats) : RationalBasisSolve
}

// B(rows(i), columns(j)) = (L U)(i,j). Factors stay in source-indexed storage; permutations
// move the completed L/U portions along with the active Schur complement without copying rows.
internal class RationalBasisFactors private constructor(
    private val original: Array<BigFraction>,
    private val layout: RationalLayout,
    private val fixed: Array<Frac128>?,
    private val big: Array<BigFraction>?,
) {
    val dimension: Int get() = layout.n

    fun ordering(): RationalBasisOrder = RationalBasisOrder(layout.rows.copyOf(), layout.columns.copyOf())

    fun solve(
        rhs: List<BigFraction>,
        transpose: Boolean = false,
        limits: RationalBasisLimits = RationalBasisLimits(),
        cancellation: Cancellation = Cancellation.Never,
    ): RationalBasisSolve {
        val meter = RationalMeter(limits, cancellation)
        return try {
            meter.dimension(dimension)
            require(rhs.size == dimension) { "RHS dimension mismatch" }
            meter.array(dimension)
            val source = Array(dimension) { i -> meter.visit(rhs[i]) }
            // Retained inputs and factors are subject to this solve's limits as well.
            for (value in original) meter.visit(value)
            val values = if (fixed != null) {
                try {
                    var fill = 0
                    for (value in fixed) {
                        meter.visit(value)
                        if (!value.isZero) meter.fill(++fill)
                    }
                    solveFixed(fixed, layout, source, transpose, meter)
                } catch (_: RationalOverflow) {
                    meter.restarts++
                    val rebuilt = buildBig(original, dimension, layout.order(), meter)
                    solveBig(rebuilt.values, rebuilt.layout, source, transpose, meter)
                }
            } else {
                val values = checkNotNull(big)
                var fill = 0
                for (value in values) {
                    meter.visit(value)
                    if (!value.isZero) meter.fill(++fill)
                }
                solveBig(values, layout, source, transpose, meter)
            }
            meter.poll()
            RationalBasisSolve.Solved(values, meter.snapshot())
        } catch (stop: RationalStop) {
            RationalBasisSolve.Declined(stop.reason, meter.snapshot())
        }
    }

    companion object {
        fun factor(
            matrix: List<List<BigFraction>>,
            proposed: RationalBasisOrder? = null,
            limits: RationalBasisLimits = RationalBasisLimits(),
            cancellation: Cancellation = Cancellation.Never,
        ): RationalBasisBuild {
            val meter = RationalMeter(limits, cancellation)
            return try {
                val n = matrix.size
                meter.dimension(n)
                for (row in matrix) {
                    meter.step()
                    require(row.size == n) { "basis must be square" }
                }
                val order = proposed?.let { copyOrder(it, n, meter) }
                meter.array(n * n)
                val original = Array(n * n) { i -> meter.visit(matrix[i / n][i % n]) }
                val factors = try {
                    val result = buildFixed(original, n, order, meter)
                    RationalBasisFactors(original, result.layout, result.values, null)
                } catch (_: RationalOverflow) {
                    meter.restarts++
                    val result = buildBig(original, n, order, meter)
                    RationalBasisFactors(original, result.layout, null, result.values)
                }
                meter.poll()
                RationalBasisBuild.Ready(factors, meter.snapshot())
            } catch (singular: RationalRank) {
                meter.pollResult { RationalBasisBuild.Singular(singular.rank, meter.snapshot()) }
            } catch (stop: RationalStop) {
                RationalBasisBuild.Declined(stop.reason, meter.snapshot())
            }
        }
    }
}

private class RationalStop(val reason: RationalBasisDecline) : RuntimeException()
private class RationalOverflow : RuntimeException()
private class RationalHintFailure : RuntimeException()
private class RationalRank(val rank: Int) : RuntimeException()

private class RationalMeter(val limits: RationalBasisLimits, private val cancellation: Cancellation) {
    private val start = TimeSource.Monotonic.markNow()
    private var work = 0L
    private var allocation = 0L
    private var peakFill = 0
    private var maxBits = 0
    private var maxIntermediateBits = 0L
    var builds = 0
    var proposedAttempts = 0
    var fallbacks = 0
    var restarts = 0

    fun poll() {
        if (cancellation()) throw RationalStop(RationalBasisDecline.CANCELLED)
        if (start.elapsedNow() >= limits.time) throw RationalStop(RationalBasisDecline.TIME)
    }

    fun pollResult(result: () -> RationalBasisBuild): RationalBasisBuild = try {
        poll()
        result()
    } catch (stop: RationalStop) {
        RationalBasisBuild.Declined(stop.reason, snapshot())
    }

    fun dimension(n: Int) {
        poll()
        if (n > limits.dimension || n.toLong() * n > Int.MAX_VALUE) throw RationalStop(RationalBasisDecline.DIMENSION)
    }

    fun step(units: Long = 1) {
        poll()
        if (units > limits.work - work) throw RationalStop(RationalBasisDecline.WORK)
        work += units
    }

    fun allocate(bytes: Long) {
        poll()
        if (bytes > limits.allocationBytes - allocation) throw RationalStop(RationalBasisDecline.MEMORY)
        allocation += bytes
    }

    fun array(size: Int) {
        step(size.toLong())
        allocate(64L + size * 8L)
    }

    fun fill(count: Int) {
        poll()
        if (count > limits.fill) throw RationalStop(RationalBasisDecline.FILL)
        peakFill = maxOf(peakFill, count)
    }

    fun bits(bits: Int) {
        poll()
        if (bits > limits.bits) throw RationalStop(RationalBasisDecline.BITS)
        maxBits = maxOf(maxBits, bits)
    }

    fun arithmetic(bound: Long) {
        step()
        if (bound > limits.bits) throw RationalStop(RationalBasisDecline.BITS)
        maxIntermediateBits = maxOf(maxIntermediateBits, bound)
        val words = (bound + 63) / 64
        step(words * words)
        // Includes result, cross products, normalization/division scratch, and fixed-width scratch.
        // A conservative deterministic allowance; the bignum allocator is not instrumented here.
        allocate(1024L + 64L * bound)
    }

    fun visit(value: BigFraction): BigFraction {
        step()
        bits(value.bits())
        return value
    }

    fun visit(value: Frac128): Frac128 {
        step()
        bits(value.bits())
        return value
    }

    fun snapshot() = RationalBasisStats(
        work, allocation, peakFill, maxBits, maxIntermediateBits, builds, proposedAttempts, fallbacks, restarts,
    )
}

private fun BigFraction.bits(): Int = maxOf(num.bitLength(), den.bitLength())

private fun wordBits(high: ULong, low: ULong): Int = if (high == 0uL) {
    64 - low.countLeadingZeroBits()
} else {
    128 - high.countLeadingZeroBits()
}

private fun Frac128.bits(): Int {
    val low = if (nHi < 0) 0uL - nLo.toULong() else nLo.toULong()
    val high = if (nHi < 0) nHi.toULong().inv() + if (nLo == 0L) 1uL else 0uL else nHi.toULong()
    return maxOf(wordBits(high, low), wordBits(dHi.toULong(), dLo.toULong()))
}

private fun toFixed(value: BigFraction, meter: RationalMeter): Frac128 {
    meter.visit(value)
    if (value.num.bitLength() > 127 || value.den.bitLength() > 127) throw RationalOverflow()
    meter.arithmetic(value.bits().toLong())
    val magnitude = value.num.abs()
    val low = magnitude.longValue(exactRequired = false)
    val high = (magnitude shr 64).longValue()
    val negative = value.signum() < 0
    return Frac128(
        if (negative) high.inv() + if (low == 0L) 1L else 0L else high,
        if (negative) -low else low,
        (value.den shr 64).longValue(),
        value.den.longValue(exactRequired = false),
    )
}

private fun toBig(value: Frac128, meter: RationalMeter): BigFraction {
    meter.arithmetic(value.bits().toLong())
    val negative = value.nHi < 0
    val low = if (negative) 0uL - value.nLo.toULong() else value.nLo.toULong()
    val high = if (negative) value.nHi.toULong().inv() + if (value.nLo == 0L) 1uL else 0uL else value.nHi.toULong()
    val magnitude = (BigInteger.fromULong(high) shl 64) + BigInteger.fromULong(low)
    val result = BigFraction.of(
        if (negative) -magnitude else magnitude,
        (BigInteger.fromLong(value.dHi) shl 64) + BigInteger.fromULong(value.dLo.toULong()),
    )
    return meter.visit(result)
}

private fun copyOrder(order: RationalBasisOrder, n: Int, meter: RationalMeter): RationalBasisOrder {
    require(order.rows.size == n && order.columns.size == n) { "order dimension mismatch" }
    fun copy(source: IntArray): IntArray {
        meter.array(n)
        val seen = BooleanArray(n)
        meter.array(n)
        return IntArray(n) { i ->
            meter.step()
            val value = source[i]
            require(value in 0 until n && !seen[value]) { "order must be a permutation" }
            seen[value] = true
            value
        }
    }
    return RationalBasisOrder(copy(order.rows), copy(order.columns))
}

private class RationalLayout(val n: Int, order: RationalBasisOrder?, meter: RationalMeter) {
    val rows: IntArray
    val columns: IntArray
    private val rowCounts: IntArray
    private val columnCounts: IntArray

    init {
        repeat(4) { meter.array(n) }
        rows = IntArray(n) { order?.rows?.get(it) ?: it }
        columns = IntArray(n) { order?.columns?.get(it) ?: it }
        rowCounts = IntArray(n)
        columnCounts = IntArray(n)
    }

    fun order(): RationalBasisOrder = RationalBasisOrder(rows, columns)
    fun index(i: Int, j: Int): Int = rows[i] * n + columns[j]

    fun pivot(k: Int, proposed: Boolean, meter: RationalMeter, nonzero: (Int) -> Boolean) {
        meter.step()
        if (proposed) {
            if (!nonzero(index(k, k))) throw RationalHintFailure()
            return
        }
        for (i in k until n) {
            meter.step()
            rowCounts[i] = 0
            columnCounts[i] = 0
        }
        for (i in k until n) {
            for (j in k until n) {
            meter.step()
            if (nonzero(index(i, j))) {
                rowCounts[i]++
                columnCounts[j]++
            }
        }
        }
        var bestRow = -1
        var bestColumn = -1
        var bestCost = Long.MAX_VALUE
        for (i in k until n) {
            for (j in k until n) {
            meter.step()
            if (nonzero(index(i, j))) {
                val cost = (rowCounts[i] - 1L) * (columnCounts[j] - 1L)
                if (cost < bestCost) {
                    bestCost = cost
                    bestRow = i
                    bestColumn = j
                }
            }
        }
        }
        if (bestRow < 0) throw RationalRank(k)
        meter.step(2)
        val row = rows[k]
        rows[k] = rows[bestRow]
        rows[bestRow] = row
        val column = columns[k]
        columns[k] = columns[bestColumn]
        columns[bestColumn] = column
    }
}

private class FixedArithmetic(private val meter: RationalMeter) {
    private val ops = Frac128Ops()

    private fun checked(value: Frac128): Frac128 {
        if (ops.overflowed()) throw RationalOverflow()
        return meter.visit(value)
    }

    fun times(a: Frac128, b: Frac128): Frac128 {
        meter.arithmetic(a.bits().toLong() + b.bits())
        return checked(ops.times(a, b))
    }

    fun minus(a: Frac128, b: Frac128): Frac128 {
        meter.arithmetic(a.bits().toLong() + b.bits() + 1)
        return checked(ops.minus(a, b))
    }

    fun reciprocal(a: Frac128): Frac128 {
        meter.arithmetic(a.bits().toLong())
        return checked(ops.reciprocal(a))
    }
}

private class BigArithmetic(private val meter: RationalMeter) {
    fun times(a: BigFraction, b: BigFraction): BigFraction {
        meter.arithmetic(a.bits().toLong() + b.bits())
        return meter.visit(a * b)
    }

    fun minus(a: BigFraction, b: BigFraction): BigFraction {
        meter.arithmetic(a.bits().toLong() + b.bits() + 1)
        return meter.visit(a - b)
    }

    fun reciprocal(a: BigFraction): BigFraction {
        meter.arithmetic(a.bits().toLong())
        return meter.visit(a.reciprocal())
    }
}

private class FixedRationalLu(val values: Array<Frac128>, val layout: RationalLayout)

private fun buildFixed(
    original: Array<BigFraction>,
    n: Int,
    proposed: RationalBasisOrder?,
    meter: RationalMeter,
): FixedRationalLu {
    var order = proposed
    while (true) {
        meter.poll()
        meter.builds++
        if (order != null) meter.proposedAttempts++
        val layout = RationalLayout(n, order, meter)
        val arithmetic = FixedArithmetic(meter)
        meter.array(original.size)
        var fill = 0
        val values = Array(original.size) { i ->
            val value = original[i]
            if (!value.isZero) meter.fill(++fill)
            toFixed(value, meter)
        }
        try {
            for (k in 0 until n) {
                layout.pivot(k, order != null, meter) { !values[it].isZero }
                val inverse = arithmetic.reciprocal(values[layout.index(k, k)])
                for (i in k + 1 until n) {
                    meter.step()
                    val lower = layout.index(i, k)
                    if (values[lower].isZero) continue
                    val multiplier = arithmetic.times(values[lower], inverse)
                    values[lower] = multiplier
                    for (j in k + 1 until n) {
                        meter.step()
                        val upper = values[layout.index(k, j)]
                        if (upper.isZero) continue
                        val cell = layout.index(i, j)
                        val old = values[cell]
                        val value = arithmetic.minus(old, arithmetic.times(multiplier, upper))
                        val nextFill = fill + (if (old.isZero) 0 else -1) + (if (value.isZero) 0 else 1)
                        meter.fill(nextFill)
                        fill = nextFill
                        values[cell] = value
                    }
                }
            }
            return FixedRationalLu(values, layout)
        } catch (_: RationalHintFailure) {
            meter.fallbacks++
            order = null
        }
    }
}

private fun solveFixed(
    values: Array<Frac128>,
    layout: RationalLayout,
    rhs: Array<BigFraction>,
    transpose: Boolean,
    meter: RationalMeter,
): List<BigFraction> {
    val arithmetic = FixedArithmetic(meter)
    val n = layout.n
    meter.array(n)
    val x = Array(n) { i ->
        val value = rhs[if (transpose) layout.columns[i] else layout.rows[i]]
        toFixed(value, meter)
    }
    for (i in 0 until n) {
        meter.step()
        for (j in 0 until i) {
            meter.step()
            val entry = values[if (transpose) layout.index(j, i) else layout.index(i, j)]
            if (!entry.isZero && !x[j].isZero) x[i] = arithmetic.minus(x[i], arithmetic.times(entry, x[j]))
        }
        if (transpose) x[i] = arithmetic.times(x[i], arithmetic.reciprocal(values[layout.index(i, i)]))
    }
    for (i in n - 1 downTo 0) {
        meter.step()
        for (j in i + 1 until n) {
            meter.step()
            val entry = values[if (transpose) layout.index(j, i) else layout.index(i, j)]
            if (!entry.isZero && !x[j].isZero) x[i] = arithmetic.minus(x[i], arithmetic.times(entry, x[j]))
        }
        if (!transpose) x[i] = arithmetic.times(x[i], arithmetic.reciprocal(values[layout.index(i, i)]))
    }
    meter.array(n)
    val result = MutableList(n) { BigFraction.ZERO }
    for (i in 0 until n) {
        meter.step()
        result[if (transpose) layout.rows[i] else layout.columns[i]] = toBig(x[i], meter)
    }
    return result
}

private class BigRationalLu(val values: Array<BigFraction>, val layout: RationalLayout)

private fun buildBig(
    original: Array<BigFraction>,
    n: Int,
    proposed: RationalBasisOrder?,
    meter: RationalMeter,
): BigRationalLu {
    var order = proposed
    while (true) {
        meter.poll()
        meter.builds++
        if (order != null) meter.proposedAttempts++
        val layout = RationalLayout(n, order, meter)
        val arithmetic = BigArithmetic(meter)
        meter.array(original.size)
        var fill = 0
        val values = Array(original.size) { i ->
            val value = original[i]
            if (!value.isZero) meter.fill(++fill)
            meter.visit(value)
        }
        try {
            for (k in 0 until n) {
                layout.pivot(k, order != null, meter) { !values[it].isZero }
                val inverse = arithmetic.reciprocal(values[layout.index(k, k)])
                for (i in k + 1 until n) {
                    meter.step()
                    val lower = layout.index(i, k)
                    if (values[lower].isZero) continue
                    val multiplier = arithmetic.times(values[lower], inverse)
                    values[lower] = multiplier
                    for (j in k + 1 until n) {
                        meter.step()
                        val upper = values[layout.index(k, j)]
                        if (upper.isZero) continue
                        val cell = layout.index(i, j)
                        val old = values[cell]
                        val value = arithmetic.minus(old, arithmetic.times(multiplier, upper))
                        val nextFill = fill + (if (old.isZero) 0 else -1) + (if (value.isZero) 0 else 1)
                        meter.fill(nextFill)
                        fill = nextFill
                        values[cell] = value
                    }
                }
            }
            return BigRationalLu(values, layout)
        } catch (_: RationalHintFailure) {
            meter.fallbacks++
            order = null
        }
    }
}

private fun solveBig(
    values: Array<BigFraction>,
    layout: RationalLayout,
    rhs: Array<BigFraction>,
    transpose: Boolean,
    meter: RationalMeter,
): List<BigFraction> {
    val arithmetic = BigArithmetic(meter)
    val n = layout.n
    meter.array(n)
    val x = Array(n) { i ->
        val value = rhs[if (transpose) layout.columns[i] else layout.rows[i]]
        meter.visit(value)
    }
    for (i in 0 until n) {
        meter.step()
        for (j in 0 until i) {
            meter.step()
            val entry = values[if (transpose) layout.index(j, i) else layout.index(i, j)]
            if (!entry.isZero && !x[j].isZero) x[i] = arithmetic.minus(x[i], arithmetic.times(entry, x[j]))
        }
        if (transpose) x[i] = arithmetic.times(x[i], arithmetic.reciprocal(values[layout.index(i, i)]))
    }
    for (i in n - 1 downTo 0) {
        meter.step()
        for (j in i + 1 until n) {
            meter.step()
            val entry = values[if (transpose) layout.index(j, i) else layout.index(i, j)]
            if (!entry.isZero && !x[j].isZero) x[i] = arithmetic.minus(x[i], arithmetic.times(entry, x[j]))
        }
        if (!transpose) x[i] = arithmetic.times(x[i], arithmetic.reciprocal(values[layout.index(i, i)]))
    }
    meter.array(n)
    val result = MutableList(n) { BigFraction.ZERO }
    for (i in 0 until n) {
        meter.step()
        result[if (transpose) layout.rows[i] else layout.columns[i]] = x[i]
    }
    return result
}
