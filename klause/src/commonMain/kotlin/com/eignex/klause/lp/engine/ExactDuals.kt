package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.PollStride
import com.eignex.koblas.SparseMatrix
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.nextUp
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.TimeSource

internal data class ExactDualLimits(
    val maxSteps: Int = 400,
    val maxBits: Int = 16384,
    val allocationBytes: Long = 1L shl 30,
    // Unlimited by default: a clock limit makes whether a certificate stands depend on machine speed and load,
    // which changes the search tree. The deterministic limits bound the work; a caller can still set a time.
    val time: Duration = Duration.INFINITE,
) {
    init {
        require(maxSteps >= 0 && maxBits > 0 && allocationBytes >= 0L)
    }
}

internal enum class ExactDualDecline { SHAPE, NOT_DYADIC, SINGULAR, NONFINITE, STALLED, STEPS, BITS, MEMORY, TIME }

/**
 * Exact duals y* of a basis, y*(i) = [scaled](i) / [denominator] with one shared denominator, verified to satisfy
 * Bᵀy* = c_B exactly. [approximations] are the doubles nearest y* within one ulp, [magnitudes] |y*| rounded up.
 */
internal class ExactDuals(
    val denominator: BigInteger,
    val scaled: Array<BigInteger>,
    val approximations: DoubleArray,
    val magnitudes: DoubleArray,
) {
    /** D·d(j) as a numerator over a positive denominator, where d(j) = c(j) − a(j)ᵀy* and D = [denominator]. */
    fun reducedTimesDenominator(exact: ExactLpModel, j: Int): Pair<BigInteger, BigInteger> {
        val cost = exact.objective.cost(j).value
        val column = if (j < exact.n) {
            exact.entries(j).map { it.row to it.number.value }
        } else {
            listOf((j - exact.n) to BigFraction.ONE)
        }
        val dyadic = Dyadic.of(cost)?.let { first ->
            var sum = first * Dyadic(denominator, 0)
            for ((row, value) in column) {
                if (scaled[row].isZero()) continue
                sum -= (Dyadic.of(value) ?: return@let null) * Dyadic(scaled[row], 0)
            }
            sum
        }
        if (dyadic != null) return dyadic.fraction()
        var sum = BigFraction.of(cost.num * denominator, cost.den)
        for ((row, value) in column) sum -= value * BigFraction.of(scaled[row], BigInteger.ONE)
        return sum.num to sum.den
    }
}

internal class ExactDualsOutcome(
    val duals: ExactDuals?,
    val decline: ExactDualDecline?,
    val steps: Int,
    val denominatorBits: Int,
)

/**
 * Exact duals of [basis] in [model]'s exact authority, by iterative refinement on one float factorization of B.
 * Each step solves for a correction from the exact residual r = c_B − Bᵀy scaled by a power of two, adds it to y
 * exactly and updates r exactly and incrementally; every basic column and cost must be dyadic, so all of it stays
 * in integers times powers of two. Once y has gained enough bits, continued fractions recover y* with a shared
 * denominator and Bᵀy* = c_B is checked in exact arithmetic. [start] seeds y; steps, bits, metered allocation and
 * time are capped, and a stop through [cancellation] or any cap declines.
 */
@Suppress("ReturnCount")
internal fun exactBasisDuals(
    model: LpModel,
    basis: Basis,
    start: DoubleArray,
    cancellation: Cancellation = Cancellation.Never,
    limits: ExactDualLimits = ExactDualLimits(),
): ExactDualsOutcome {
    val exact = model.exactState?.model ?: return declined(ExactDualDecline.SHAPE)
    val m = exact.m
    val basic = basis.basicVars
    if (basic.size != m || start.size != m || basic.any { it !in 0 until exact.numVars }) {
        return declined(ExactDualDecline.SHAPE)
    }
    val meter = ExactDualMeter(limits, cancellation)
    return try {
        ExactDualRefinement(model, exact, basic, meter).run(start)
    } catch (stop: ExactDualStop) {
        declined(stop.reason, meter.steps)
    }
}

private fun declined(reason: ExactDualDecline, steps: Int = 0) = ExactDualsOutcome(null, reason, steps, 0)

private class ExactDualStop(val reason: ExactDualDecline) : RuntimeException()

private class ExactDualMeter(val limits: ExactDualLimits, private val cancellation: Cancellation) {
    private val started = TimeSource.Monotonic.markNow()
    private val stride = PollStride()
    private var allocation = 0L
    var steps = 0

    fun poll() {
        if (cancellation() || started.elapsedNow() >= limits.time) throw ExactDualStop(ExactDualDecline.TIME)
    }

    // A deterministic allowance per big integer produced; the bignum allocator is not instrumented.
    fun charge(bits: Int) {
        if (stride.due(1L + bits / 64)) poll()
        if (bits > limits.maxBits * 2 + 256) throw ExactDualStop(ExactDualDecline.BITS)
        val bytes = 48L + bits / 4
        if (bytes > limits.allocationBytes - allocation) throw ExactDualStop(ExactDualDecline.MEMORY)
        allocation += bytes
    }

    fun <T : Dyadic> dyadic(value: T): T {
        charge(value.width)
        return value
    }

    fun integer(value: BigInteger): BigInteger {
        charge(value.bitLength())
        return value
    }
}

private class ExactDualRefinement(
    model: LpModel,
    private val exact: ExactLpModel,
    private val basic: IntArray,
    private val meter: ExactDualMeter,
) {
    private val m = exact.m
    private val scaling = LpScalingView.create(model)

    // Basic column k as rows and dyadic values, and its dyadic cost.
    private val rows = Array(m) { IntArray(0) }
    private val values = Array(m) { emptyArray<Dyadic>() }
    private val costs = Array(m) { Dyadic.ZERO }

    private fun load() {
        for (k in 0 until m) {
            val j = basic[k]
            costs[k] = Dyadic.of(exact.objective.cost(j).value) ?: throw ExactDualStop(ExactDualDecline.NOT_DYADIC)
            if (j < exact.n) {
                val entries = exact.entries(j)
                rows[k] = IntArray(entries.size) { entries[it].row }
                values[k] = Array(entries.size) {
                    Dyadic.of(entries[it].number.value) ?: throw ExactDualStop(ExactDualDecline.NOT_DYADIC)
                }
            } else {
                rows[k] = intArrayOf(j - exact.n)
                values[k] = arrayOf(Dyadic.ONE)
            }
        }
    }

    // Float factors of B in the engine's scaled space: B_s = P·B·Q with P = 2^p and Q = 2^q, so Bᵀy = r becomes
    // B_sᵀ(P⁻¹y) = Q·r.
    private fun factor(): KotlinBasisSolver {
        val columns = List(m) { k ->
            val j = basic[k]
            if (j < exact.n) {
                val entries = ArrayList<Pair<Int, Double>>()
                scaling.forEachInColumn(j) { i, v -> if (v != 0.0) entries += i to v }
                entries
            } else {
                listOf((j - exact.n) to 1.0)
            }
        }
        val solver = KotlinBasisSolver(SparseMatrix.ofColumns(m, m, columns))
        if (!solver.refactorize(IntArray(m) { it }) || solver.singular) {
            solver.close()
            throw ExactDualStop(ExactDualDecline.SINGULAR)
        }
        return solver
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    fun run(start: DoubleArray): ExactDualsOutcome {
        load()
        val y = Array(m) { Dyadic.of(start[it]) ?: throw ExactDualStop(ExactDualDecline.NONFINITE) }
        val residual = Array(m) { k -> residualOf(k, y) }
        // log2 of each basic equation's terms, so precision reads relative to the row's own size; a row whose terms
        // vanish reads against the largest row instead.
        val terms = DoubleArray(m) { k ->
            var sum = abs(costs[k].toDouble(0))
            val r = rows[k]
            val v = values[k]
            for (t in r.indices) sum += abs(v[t].toDouble(0) * start[r[t]])
            sum
        }
        val floor = max((terms.maxOrNull() ?: 0.0) * TERMS_FLOOR, Double.MIN_VALUE)
        val scale = DoubleArray(m) { ln(max(terms[it], floor)) / LN2 }
        val solver = factor()
        try {
            val rhs = DoubleArray(m)
            val solution = DoubleArray(m)
            val vector = IndexedVector(m)
            var nextAttempt = FIRST_ATTEMPT_BITS
            var best = Int.MIN_VALUE
            var stalled = 0
            while (true) {
                meter.poll()
                var precision = Int.MAX_VALUE
                var top = Int.MIN_VALUE
                for (k in 0 until m) {
                    val r = residual[k]
                    if (r.isZero) continue
                    top = maxOf(top, r.top)
                    precision = minOf(precision, (scale[k] - r.top).toInt() - PRECISION_MARGIN)
                }
                if (top == Int.MIN_VALUE) return verified(exactOf(y))
                if (precision >= nextAttempt) {
                    reconstruct(y, precision)?.let { return verified(it) }
                    nextAttempt = precision + precision / 4
                }
                if (precision > limits.maxBits) throw ExactDualStop(ExactDualDecline.BITS)
                if (meter.steps >= limits.maxSteps) throw ExactDualStop(ExactDualDecline.STEPS)
                if (precision >= best + MIN_GAIN_BITS) {
                    best = precision
                    stalled = 0
                } else if (++stalled >= STALL_STEPS) {
                    throw ExactDualStop(ExactDualDecline.STALLED)
                }
                val shift = -top
                for (k in 0 until m) {
                    rhs[k] = residual[k].toDouble(shift + scaling.columnExponents[basic[k]])
                    if (!rhs[k].isFinite()) throw ExactDualStop(ExactDualDecline.NONFINITE)
                }
                vector.scatter(rhs)
                solver.btran(vector)
                vector.gather(solution)
                val correction = Array(m) { i ->
                    val d = Dyadic.of(solution[i]) ?: throw ExactDualStop(ExactDualDecline.NONFINITE)
                    if (d.isZero) d else Dyadic(d.man, d.exp + scaling.rowExponents[i] - shift)
                }
                for (i in 0 until m) if (!correction[i].isZero) y[i] = meter.dyadic(y[i] + correction[i])
                for (k in 0 until m) {
                    var r = residual[k]
                    val rowsK = rows[k]
                    val valuesK = values[k]
                    for (t in rowsK.indices) {
                        val d = correction[rowsK[t]]
                        if (!d.isZero) r = meter.dyadic(r - valuesK[t] * d)
                    }
                    residual[k] = r
                }
                meter.steps++
            }
        } finally {
            solver.close()
        }
    }

    private val limits get() = meter.limits

    private fun residualOf(k: Int, y: Array<Dyadic>): Dyadic {
        var r = costs[k]
        val rowsK = rows[k]
        val valuesK = values[k]
        for (t in rowsK.indices) r = meter.dyadic(r - valuesK[t] * y[rowsK[t]])
        return r
    }

    // A dyadic y already solves the system exactly: its shared denominator is a power of two.
    private fun exactOf(y: Array<Dyadic>): Pair<BigInteger, Array<BigInteger>> {
        val low = y.filter { !it.isZero }.minOfOrNull { it.exp } ?: 0
        val shift = maxOf(0, -low)
        val scaled = Array(m) { i ->
            val v = y[i]
            meter.integer(if (v.isZero) BigInteger.ZERO else v.man shl (v.exp + shift))
        }
        return (BigInteger.ONE shl shift) to scaled
    }

    // Continued fractions on D·y(i), D the product of the denominators found so far, so a later component needs
    // only the bits its own new factor costs; each convergent's denominator stays within half the precision left
    // after the value's size and D. Null when the precision runs out first or the result fails to verify.
    private fun reconstruct(y: Array<Dyadic>, precision: Int): Pair<BigInteger, Array<BigInteger>>? {
        val top = y.filter { !it.isZero }.maxOfOrNull { it.top } ?: 0
        var common = BigInteger.ONE
        val numerators = Array(m) { BigInteger.ZERO }
        val commonAt = Array(m) { BigInteger.ONE }
        for (i in 0 until m) {
            val v = y[i]
            if (v.isZero) {
                numerators[i] = BigInteger.ZERO
                commonAt[i] = common
                continue
            }
            val limitBits = (precision - maxOf(top, 0) - common.bitLength()) / 2 - 2
            if (limitBits < 1) return null
            val limit = BigInteger.ONE shl limitBits
            var a = meter.integer(v.man.abs() * common)
            var b = BigInteger.ONE
            if (v.exp >= 0) a = meter.integer(a shl v.exp) else b = b shl -v.exp
            var p0 = BigInteger.ZERO
            var p1 = BigInteger.ONE
            var q0 = BigInteger.ONE
            var q1 = BigInteger.ZERO
            while (!b.isZero()) {
                val division = a.divrem(b)
                val quotient = meter.integer(division.quotient)
                val q = meter.integer(quotient * q1 + q0)
                if (q > limit) break
                val p = meter.integer(quotient * p1 + p0)
                p0 = p1
                p1 = p
                q0 = q1
                q1 = q
                a = b
                b = meter.integer(division.remainder)
            }
            common = meter.integer(common * q1)
            numerators[i] = if (v.man.signum() < 0) -p1 else p1
            commonAt[i] = common
        }
        val scaled = Array(m) { meter.integer(numerators[it] * (common / commonAt[it])) }
        return (common to scaled).takeIf { solves(it.first, it.second) }
    }

    // Bᵀ(D·y*) = D·c_B, exactly.
    private fun solves(denominator: BigInteger, scaled: Array<BigInteger>): Boolean {
        val d = Dyadic(denominator, 0)
        for (k in 0 until m) {
            var sum = meter.dyadic(costs[k] * d)
            val rowsK = rows[k]
            val valuesK = values[k]
            for (t in rowsK.indices) {
                val s = scaled[rowsK[t]]
                if (!s.isZero()) sum = meter.dyadic(sum - valuesK[t] * Dyadic(s, 0))
            }
            if (!sum.isZero) return false
        }
        return true
    }

    @Suppress("ThrowsCount")
    private fun verified(candidate: Pair<BigInteger, Array<BigInteger>>): ExactDualsOutcome {
        val (denominator, scaled) = candidate
        if (!solves(denominator, scaled)) throw ExactDualStop(ExactDualDecline.STALLED)
        val approximations = DoubleArray(m)
        val magnitudes = DoubleArray(m)
        for (i in 0 until m) {
            val numerator = scaled[i]
            if (numerator.isZero()) continue
            val above = quotientAbove(numerator.abs(), denominator) ?: throw ExactDualStop(ExactDualDecline.NONFINITE)
            val nearest = quotientNearest(numerator.abs(), denominator)
            // A subnormal loses the relative accuracy the check's rounding band assumes.
            if (!nearest.isFinite() || nearest < MIN_NORMAL_DUAL) throw ExactDualStop(ExactDualDecline.NONFINITE)
            approximations[i] = if (numerator.signum() < 0) -nearest else nearest
            magnitudes[i] = above
        }
        return ExactDualsOutcome(
            ExactDuals(denominator, scaled, approximations, magnitudes),
            null,
            meter.steps,
            denominator.bitLength(),
        )
    }
}

// An exact dyadic rational man·2^exp. Sums align to the lower exponent, so a mantissa spans only the bits its value
// carries and needs no normalizing.
private class Dyadic(val man: BigInteger, val exp: Int) {
    val isZero: Boolean get() = man.isZero()

    // 2^(top − 1) ≤ |value| < 2^top.
    val top: Int get() = man.abs().bitLength() + exp

    val width: Int get() = man.abs().bitLength()

    operator fun plus(other: Dyadic): Dyadic {
        if (isZero) return other
        if (other.isZero) return this
        val e = minOf(exp, other.exp)
        return Dyadic((man shl (exp - e)) + (other.man shl (other.exp - e)), e)
    }

    operator fun minus(other: Dyadic): Dyadic = this + Dyadic(-other.man, other.exp)

    operator fun times(other: Dyadic): Dyadic =
        if (isZero || other.isZero) ZERO else Dyadic(man * other.man, exp + other.exp)

    // The value times 2^shift to about double precision; beyond the double range it overflows or flushes to zero.
    fun toDouble(shift: Int): Double {
        if (isZero) return 0.0
        val magnitude = man.abs()
        val drop = maxOf(0, magnitude.bitLength() - LEADING_BITS)
        val leading = (magnitude shr drop).longValue(exactRequired = true).toDouble()
        val value = timesPowerOfTwo(leading, exp.toLong() + drop + shift)
        return if (man.signum() < 0) -value else value
    }

    fun fraction(): Pair<BigInteger, BigInteger> =
        if (exp >= 0) (man shl exp) to BigInteger.ONE else man to (BigInteger.ONE shl -exp)

    companion object {
        val ZERO = Dyadic(BigInteger.ZERO, 0)
        val ONE = Dyadic(BigInteger.ONE, 0)

        fun of(value: Double): Dyadic? {
            if (value == 0.0) return ZERO
            if (!value.isFinite()) return null
            val bits = value.toRawBits()
            val biased = ((bits ushr 52) and 0x7FFL).toInt()
            var mantissa = bits and 0xFFFFFFFFFFFFFL
            var exponent = if (biased == 0) {
                -1074
            } else {
                mantissa = mantissa or (1L shl 52)
                biased - 1075
            }
            val zeros = mantissa.countTrailingZeroBits()
            mantissa = mantissa shr zeros
            exponent += zeros
            return Dyadic(BigInteger.fromLong(if (bits < 0L) -mantissa else mantissa), exponent)
        }

        // Null when the denominator is not a power of two.
        fun of(value: BigFraction): Dyadic? {
            val den = value.den
            if (den != BigInteger.ONE shl (den.bitLength() - 1)) return null
            return Dyadic(value.num, 1 - den.bitLength())
        }
    }
}

/** [numerator] / [denominator] for positive integers, rounded up to a double; null past the double range. */
internal fun quotientAbove(numerator: BigInteger, denominator: BigInteger): Double? {
    if (numerator.isZero()) return 0.0
    val shift = numerator.bitLength() - denominator.bitLength() - QUOTIENT_BITS
    val quotient = if (shift >= 0) numerator / (denominator shl shift) else (numerator shl -shift) / denominator
    // The floored quotient plus one bounds the true one; its double and every power-of-two step round up.
    var bound = (quotient + BigInteger.ONE).doubleValue(exactRequired = false).nextUp()
    var exponent = shift
    while (exponent > 0) {
        val step = minOf(exponent, POW2_STEP)
        bound *= 2.0.pow(step)
        exponent -= step
    }
    while (exponent < 0) {
        val step = minOf(-exponent, POW2_STEP)
        bound = (bound / 2.0.pow(step)).nextUp()
        exponent += step
    }
    return bound.takeIf { it.isFinite() }
}

// [numerator] / [denominator] for positive integers within one ulp while normal: a 62-bit floored quotient rounded
// once to a double, then scaled exactly by powers of two.
private fun quotientNearest(numerator: BigInteger, denominator: BigInteger): Double {
    val shift = numerator.bitLength() - denominator.bitLength() - LEADING_BITS
    val quotient = if (shift >= 0) numerator / (denominator shl shift) else (numerator shl -shift) / denominator
    val leading = if (quotient.bitLength() > LEADING_BITS) {
        (quotient shr 1).longValue(exactRequired = true).toDouble() * 2.0
    } else {
        quotient.longValue(exactRequired = true).toDouble()
    }
    return timesPowerOfTwo(leading, shift.toLong())
}

private fun timesPowerOfTwo(value: Double, exponent: Long): Double {
    var result = value
    var left = exponent.coerceIn(-POW2_LIMIT, POW2_LIMIT)
    while (left > 0 && result.isFinite()) {
        val step = minOf(left, POW2_STEP.toLong()).toInt()
        result *= 2.0.pow(step)
        left -= step
    }
    while (left < 0 && result != 0.0) {
        val step = minOf(-left, POW2_STEP.toLong()).toInt()
        result /= 2.0.pow(step)
        left += step
    }
    return result
}

private const val QUOTIENT_BITS = 60
private const val LEADING_BITS = 62
private const val POW2_STEP = 1000
private const val POW2_LIMIT = 1L shl 20
private const val LN2 = 0.6931471805599453
private const val TERMS_FLOOR = 5.421010862427522e-20
private const val MIN_NORMAL_DUAL = 2.2250738585072014e-308

// Reconstruction first tries at this precision and then at each quarter more; the margin keeps the precision
// estimate below the bits the residual actually vouches for.
private const val FIRST_ATTEMPT_BITS = 128
private const val PRECISION_MARGIN = 40

// A refinement whose precision gains fewer bits than this over this many steps has met a basis too ill-conditioned
// for its float factors.
private const val MIN_GAIN_BITS = 4
private const val STALL_STEPS = 3
