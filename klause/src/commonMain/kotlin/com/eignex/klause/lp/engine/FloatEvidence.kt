package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.math.abs
import kotlin.math.max

/** Primal and dual feasibility tolerance of float LP evidence, the HiGHS default. */
internal const val FLOAT_EVIDENCE_TOLERANCE: Double = 1e-7

// Unit roundoff of binary64: a reduced cost summed from k terms, each a double standing in for an exact cost or
// coefficient, carries at most about (k + 4)·u·Σ|terms| error.
private const val UNIT_ROUNDOFF: Double = 1.1102230246251565e-16

// Duals from a float basis solve carry error relative to the terms they price, beyond the summation's own rounding.
// Purely relative, so a column priced only by tiny terms keeps a tiny margin; capped by the float tolerance itself.
// It is sound only multiplied by a finite range: a column whose step only rows limit gets that range implied first.
private const val DUAL_NOISE: Double = 1e-12

// Exact duals enter the float sums as their nearest doubles, each within an ulp.
private const val EXACT_DUAL_ROUNDING: Int = 2

internal enum class FloatDualVerdict {
    ACCEPTED,

    /** Refused, and some reduced cost is wrong-signed beyond doubt under the duals checked. */
    WRONG_SIGNED,
    REFUSED,
}

/**
 * Whether [result]'s duals prove its objective optimal within [tolerance]: every reduced cost off its sign, a basic
 * column's included, could improve the objective by at most its magnitude plus its noise times its column's range,
 * and the total of those improvements must stay within [tolerance] of the objective's own size, every constant
 * included — [offset] is the minimized objective's constant held outside [model], in source units. A column with
 * no opposite bound spans the range its rows imply; a wrong-signed reduced cost beyond its summation's rounding on
 * a column still unbounded rejects, however small. Read from the binary64 projection of the exact authority, which
 * is the working model [result] was solved on. With [exactDuals], the bound is taken for those duals instead of
 * [result]'s.
 */
internal fun floatDualFeasible(
    model: LpModel,
    result: FloatLpResult,
    offset: Double = 0.0,
    tolerance: Double = FLOAT_EVIDENCE_TOLERANCE,
    exactDuals: ExactDuals? = null,
): Boolean = floatDualVerdict(model, result, offset, tolerance, exactDuals) == FloatDualVerdict.ACCEPTED

// The Lagrangian bound holds for any duals, so exact duals change only which reduced costs are decided exactly:
// their doubles lie within an ulp of them, which two more units of rounding cover, and inside the band the exact
// value decides. A basic column's exact reduced cost is exactly its own, so an unbounded basic column is priced
// like any other rather than left to the engine.
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun floatDualVerdict(
    model: LpModel,
    result: FloatLpResult,
    offset: Double = 0.0,
    tolerance: Double = FLOAT_EVIDENCE_TOLERANCE,
    exactDuals: ExactDuals? = null,
): FloatDualVerdict {
    val refused = FloatDualVerdict.REFUSED
    val exact = model.exactState?.model ?: return refused
    val duals = exactDuals?.approximations ?: result.duals
    val status = result.basis.status
    if (duals.size != exact.m || status.size != exact.numVars || result.primal.size < exact.n) return refused
    // The float objective is the minimized source value, every model constant and bound shift included; the primal
    // is unshifted, so summing costs over it would count a shifted column twice. Reduced costs are in scaled units.
    val objective = (result.objective + offset) * exact.objective.scale.approximation
    if (!objective.isFinite()) return refused
    val rows by lazy { RowView(exact) }
    val dualRounding = if (exactDuals == null) 0 else EXACT_DUAL_ROUNDING
    var wrongSigned = false
    // Nonbasic finite-range columns whose sign the doubles leave to their rounding, with whether each rests on a
    // bound: only a refusal needs their exact signs.
    val undecided = ArrayList<Pair<Int, Boolean>>()
    fun refusal(): FloatDualVerdict {
        if (!wrongSigned) {
            wrongSigned = undecided.any { (j, resting) ->
                (exactWrong(exact, duals, exactDuals, j, status[j], resting) ?: 1.0) > 0.0
            }
        }
        return if (wrongSigned) FloatDualVerdict.WRONG_SIGNED else refused
    }
    var improvement = 0.0
    for (j in 0 until exact.numVars) {
        val cost = exact.objective.cost(j).approximation
        if (exact.column(j).bounds.fixed) continue
        var priced = 0.0
        var terms = abs(cost)
        var count = 1
        if (j < exact.n) {
            for (entry in exact.entries(j)) {
                val term = entry.number.approximation * duals[entry.row]
                priced += term
                terms += abs(term)
                count++
            }
        } else {
            priced = duals[j - exact.n]
            terms += abs(priced)
            count++
        }
        val reduced = cost - priced
        if (!reduced.isFinite()) return refusal()
        val bounds = exact.column(j).bounds
        val lower = bounds.lower?.number?.approximation ?: Double.NEGATIVE_INFINITY
        val upper = bounds.upper?.number?.approximation ?: Double.POSITIVE_INFINITY
        // A basic column's reduced cost is zero only up to the basis solve's residual, and a status resting on a
        // missing bound leaves the column free to move either way: both count over the whole box. Under the
        // engine's duals that residual is not bounded by the summation's rounding, so an unbounded basic column is
        // left to the engine's pricing.
        val resting = when (status[j]) {
            VarStatus.AT_LOWER -> lower.isFinite()
            VarStatus.AT_UPPER -> upper.isFinite()
            else -> false
        }
        var wrong = when {
            !resting -> abs(reduced)
            status[j] == VarStatus.AT_LOWER -> -reduced
            else -> reduced
        }
        val rounding = (count + 4 + dualRounding) * UNIT_ROUNDOFF * terms
        val noise = max(rounding, minOf(DUAL_NOISE * terms, tolerance))
        if (wrong > rounding) wrongSigned = true
        var range = max(upper - lower, 0.0)
        if (range.isInfinite()) {
            if (status[j] == VarStatus.BASIC && exactDuals == null) continue
            // Within its rounding the double's sign says nothing: the exact reduced cost of these duals decides.
            if (wrong <= -rounding) continue
            if (wrong <= rounding) {
                wrong = exactWrong(exact, duals, exactDuals, j, status[j], resting) ?: return refusal()
                if (wrong > 0.0) wrongSigned = true
            }
            if (wrong <= 0.0) continue
            range = rows.impliedRange(j)
            if (range.isInfinite()) return refusal()
        } else if (exactDuals != null && status[j] != VarStatus.BASIC && wrong > -rounding && wrong <= rounding) {
            undecided += j to resting
        }
        improvement += (max(wrong, 0.0) + noise) * range
        if (!improvement.isFinite()) return refusal()
    }
    val limit = tolerance * max(1.0, abs(objective))
    if (improvement > limit) return refusal()
    // The padded residuals decide cheaply; a point whose padding alone would refuse it has its residuals taken exactly.
    val weights = exactDuals?.magnitudes ?: result.duals
    val padded = dualResidualGap(exact, result, weights, padded = true) ?: return refusal()
    if (improvement + padded <= limit) return FloatDualVerdict.ACCEPTED
    val residual = dualResidualGap(exact, result, weights, padded = false) ?: return refusal()
    return if (improvement + residual <= limit) FloatDualVerdict.ACCEPTED else refusal()
}

/**
 * The duality gap the reported point's row residuals add: Σ|y(i)|·|A(i)·ẑ + s(i) − b(i)| over rows whose slack is
 * nonbasic, so at its bound, with |y(i)| read from [duals] (a magnitude bound serves). The reported objective is the
 * point's own, and a residual below the source tolerance still counts once a large dual multiplies it. A basic slack
 * is defined by its row and leaves no residual. When [padded], each residual is summed in doubles and padded by its
 * rounding; otherwise it is taken exactly from the point's doubles. Null when a nonbasic slack rests on no bound or a
 * value is not finite.
 */
private fun dualResidualGap(exact: ExactLpModel, result: FloatLpResult, duals: DoubleArray, padded: Boolean): Double? =
    if (padded) paddedResidualGap(exact, result, duals) else exactResidualGap(exact, result, duals)

private fun nonbasicSlack(exact: ExactLpModel, result: FloatLpResult, i: Int): ExactLpNumber? {
    val bounds = exact.column(exact.n + i).bounds
    return when (result.basis.status[exact.n + i]) {
        VarStatus.AT_LOWER -> bounds.lower?.number
        VarStatus.AT_UPPER -> bounds.upper?.number
        else -> ExactLpNumber.of(0L)
    }
}

private fun exactResidualGap(exact: ExactLpModel, result: FloatLpResult, duals: DoubleArray): Double? {
    val activity = Array(exact.m) { BigFraction.ZERO }
    for (j in 0 until exact.n) {
        val shifted = (BigFraction.ofDouble(result.primal[j]) ?: return null) - exact.column(j).origin.value
        if (shifted.isZero) continue
        for (entry in exact.entries(j)) activity[entry.row] += entry.number.value * shifted
    }
    var gap = 0.0
    for (i in 0 until exact.m) {
        if (result.basis.status[exact.n + i] == VarStatus.BASIC || duals[i] == 0.0) continue
        val slack = nonbasicSlack(exact, result, i) ?: return null
        val residual = magnitudeAbove(activity[i] + slack.value - exact.rhs(i).value) ?: return null
        // The residual is already rounded up; the factor covers the product.
        gap += abs(duals[i]) * residual * (1 + 2 * UNIT_ROUNDOFF)
    }
    return gap * (1 + exact.m * UNIT_ROUNDOFF)
}

private fun paddedResidualGap(exact: ExactLpModel, result: FloatLpResult, duals: DoubleArray): Double? {
    val activity = DoubleArray(exact.m)
    val magnitude = DoubleArray(exact.m)
    val terms = IntArray(exact.m)
    for (j in 0 until exact.n) {
        val origin = exact.column(j).origin.approximation
        val shifted = result.primal[j] - origin
        for (entry in exact.entries(j)) {
            val a = entry.number.approximation
            activity[entry.row] += a * shifted
            magnitude[entry.row] += abs(a) * (abs(result.primal[j]) + abs(origin))
            terms[entry.row]++
        }
    }
    var gap = 0.0
    for (i in 0 until exact.m) {
        val slackColumn = exact.n + i
        if (result.basis.status[slackColumn] == VarStatus.BASIC || duals[i] == 0.0) continue
        val slack = nonbasicSlack(exact, result, i)?.approximation ?: return null
        val rhs = exact.rhs(i).approximation
        val residual = abs(activity[i] + slack - rhs) +
            (terms[i] + 8) * UNIT_ROUNDOFF * (magnitude[i] + abs(slack) + abs(rhs))
        gap += abs(duals[i]) * residual
    }
    return gap
}

// How far column j's reduced cost under [duals], taken as exact rationals, lies on the wrong side of [status] (both
// sides when it rests on no bound), rounded up to a double; null when a dual is not finite. [exactDuals], when
// given, are the rationals [duals] approximate and are priced instead.
@Suppress("LongParameterList")
private fun exactWrong(
    exact: ExactLpModel,
    duals: DoubleArray,
    exactDuals: ExactDuals?,
    j: Int,
    status: VarStatus,
    resting: Boolean,
): Double? {
    val reduced = if (exactDuals != null) {
        val (numerator, denominator) = exactDuals.reducedTimesDenominator(exact, j)
        BigFraction.of(numerator, denominator * exactDuals.denominator)
    } else {
        var sum = exact.objective.cost(j).value
        if (j < exact.n) {
            for (entry in exact.entries(j)) {
                sum -= entry.number.value * (BigFraction.ofDouble(duals[entry.row]) ?: return null)
            }
        } else {
            sum -= BigFraction.ofDouble(duals[j - exact.n]) ?: return null
        }
        sum
    }
    val wrong = when {
        !resting -> if (reduced.signum() < 0) reduced.negated() else reduced
        status == VarStatus.AT_LOWER -> reduced.negated()
        else -> reduced
    }
    return if (wrong.signum() <= 0) 0.0 else magnitudeAbove(wrong)
}

/**
 * |[value]| rounded up to a double, null when it exceeds the double range. [BigFraction.toDouble] divides two
 * doubles, so a denominator past 2^1024 reads a real magnitude as 0; this takes an integer quotient of about 60 bits
 * instead and scales it by powers of two.
 */
private fun magnitudeAbove(value: BigFraction): Double? =
    quotientAbove(if (value.num.signum() < 0) value.num.negate() else value.num, value.den)

/**
 * The rows of [exact] as equalities `Σ a(i, j)·z(j) + s(i) = b(i)` over shifted structural columns and their slacks,
 * with each row's least and greatest activity, to imply a column's range from the other columns' bounds. Every
 * implied bound is widened outward by the rounding its doubles can carry, so a range is never understated.
 */
private class RowView(private val exact: ExactLpModel) {
    private val lower = DoubleArray(exact.numVars) {
        exact.column(it).bounds.lower?.number?.approximation ?: Double.NEGATIVE_INFINITY
    }
    private val upper = DoubleArray(exact.numVars) {
        exact.column(it).bounds.upper?.number?.approximation ?: Double.POSITIVE_INFINITY
    }

    // Finite parts of each row's activity extremes, their absolute sums, how many terms are infinite, and the
    // row's term count.
    private val least = DoubleArray(exact.m)
    private val most = DoubleArray(exact.m)
    private val leastMagnitude = DoubleArray(exact.m)
    private val mostMagnitude = DoubleArray(exact.m)
    private val leastInfinite = IntArray(exact.m)
    private val mostInfinite = IntArray(exact.m)
    private val terms = IntArray(exact.m)

    init {
        for (i in 0 until exact.m) add(i, exact.n + i, 1.0)
        for (j in 0 until exact.n) for (entry in exact.entries(j)) add(entry.row, j, entry.number.approximation)
    }

    private fun add(i: Int, j: Int, a: Double) {
        val low = if (a > 0) a * lower[j] else a * upper[j]
        val high = if (a > 0) a * upper[j] else a * lower[j]
        // A subnormal coefficient carries no relative accuracy, so its term is as good as unbounded.
        if (subnormal(a)) {
            leastInfinite[i]++
            mostInfinite[i]++
            terms[i]++
            return
        }
        if (low.isFinite()) {
            least[i] += low
            leastMagnitude[i] += abs(low)
        } else {
            leastInfinite[i]++
        }
        if (high.isFinite()) {
            most[i] += high
            mostMagnitude[i] += abs(high)
        } else {
            mostInfinite[i]++
        }
        terms[i]++
    }

    /** Width of [j]'s box intersected with the interval every row through it implies, never below zero. */
    fun impliedRange(j: Int): Double {
        var low = lower[j]
        var high = upper[j]
        if (j >= exact.n) {
            low = max(low, implied(j - exact.n, j, 1.0, lowSide = true))
            high = minOf(high, implied(j - exact.n, j, 1.0, lowSide = false))
        } else {
            for (entry in exact.entries(j)) {
                val a = entry.number.approximation
                low = max(low, implied(entry.row, j, a, lowSide = true))
                high = minOf(high, implied(entry.row, j, a, lowSide = false))
            }
        }
        return max(high - low, 0.0)
    }

    // One side of the interval row i implies for column j, (b(i) − the others' activity) / a(i, j), widened by
    // γ·(Σ|terms| + |b|)/|a| with γ covering the row's summation, the doubles standing in for its rationals, and
    // the division.
    private fun implied(i: Int, j: Int, a: Double, lowSide: Boolean): Double {
        // The low side of j comes from the others' greatest activity when a > 0, their least when a < 0.
        val fromMost = lowSide == a > 0
        if (subnormal(a)) return if (lowSide) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        val own = if (fromMost == a > 0) a * upper[j] else a * lower[j]
        val finite = if (fromMost) most[i] else least[i]
        val magnitude = if (fromMost) mostMagnitude[i] else leastMagnitude[i]
        val infinite = if (fromMost) mostInfinite[i] else leastInfinite[i]
        val unbounded = if (lowSide) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        val others = when {
            own.isFinite() -> if (infinite == 0) finite - own else return unbounded
            infinite == 1 -> finite
            else -> return unbounded
        }
        val rhs = exact.rhs(i).approximation
        val bound = (rhs - others) / a
        val widening = (terms[i] + 8) * UNIT_ROUNDOFF * (magnitude + abs(rhs)) / abs(a)
        return if (lowSide) bound - widening else bound + widening
    }

    private fun subnormal(a: Double): Boolean = a != 0.0 && abs(a) < MIN_NORMAL
}

private const val MIN_NORMAL: Double = 2.2250738585072014e-308
