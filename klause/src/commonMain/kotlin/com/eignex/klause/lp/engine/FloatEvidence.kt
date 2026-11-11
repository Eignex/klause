package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.util.numbers.negate
import com.eignex.util.numbers.signum
import com.eignex.util.numbers.times
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.nextUp
import kotlin.math.sign

/** Primal and dual feasibility tolerance of float LP evidence. */
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
    if (model.exactState == null && !model.floatProjectionAuthoritative()) return refused
    if (exactDuals != null && model.exactState == null) return refused
    val exact = FloatEvidenceModel(model)
    val duals = exactDuals?.approximations ?: result.duals
    val status = result.basis.status
    if (duals.size != exact.m || status.size != exact.numVars || result.primal.size < exact.n) return refused
    // The float objective is the minimized source value, every model constant and bound shift included; the primal
    // is unshifted, so summing costs over it would count a shifted column twice. Reduced costs are in scaled units.
    val objective = (result.objective + offset) * exact.scale
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
        val cost = exact.cost(j)
        if (exact.fixed(j)) continue
        var priced = 0.0
        var terms = abs(cost)
        var count = 1
        if (j < exact.n) {
            exact.forEachInColumn(j) { row, coefficient ->
                val term = coefficient * duals[row]
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
        val lower = exact.lower(j)
        val upper = exact.upper(j)
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
    val limit = tolerance * max(1.0, abs(objective) - objectiveRounding(exact, result, offset))
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
private fun dualResidualGap(
    exact: FloatEvidenceModel,
    result: FloatLpResult,
    duals: DoubleArray,
    padded: Boolean,
): Double? = if (padded) paddedResidualGap(exact, result, duals) else exactResidualGap(exact, result, duals)

private fun nonbasicSlack(exact: FloatEvidenceModel, result: FloatLpResult, i: Int): BigFraction? {
    val column = exact.n + i
    exact.model.exactState?.model?.column(column)?.bounds?.let { bounds ->
        return when (result.basis.status[column]) {
            VarStatus.AT_LOWER -> bounds.lower?.number?.value
            VarStatus.AT_UPPER -> bounds.upper?.number?.value
            else -> BigFraction.ZERO
        }
    }
    return when (result.basis.status[column]) {
        VarStatus.AT_LOWER -> if (exact.lower(column).isFinite()) exact.model.exactLower(column) else null
        VarStatus.AT_UPPER -> if (exact.upper(column).isFinite()) exact.model.exactUpper(column) else null
        else -> BigFraction.ZERO
    }
}

private fun nonbasicSlackD(exact: FloatEvidenceModel, result: FloatLpResult, i: Int): Double? {
    val column = exact.n + i
    exact.model.exactState?.model?.column(column)?.bounds?.let { bounds ->
        return when (result.basis.status[column]) {
            VarStatus.AT_LOWER -> bounds.lower?.number?.approximation
            VarStatus.AT_UPPER -> bounds.upper?.number?.approximation
            else -> 0.0
        }
    }
    return when (result.basis.status[column]) {
        VarStatus.AT_LOWER -> exact.lower(column).takeIf { it.isFinite() }
        VarStatus.AT_UPPER -> exact.upper(column).takeIf { it.isFinite() }
        else -> 0.0
    }
}

// How far the float objective can overstate the true one: its summation's rounding over every priced term and the
// constants, in the scaled units the limit is taken in. The limit scales with the smaller size, never the larger.
private fun objectiveRounding(exact: FloatEvidenceModel, result: FloatLpResult, offset: Double): Double {
    val scale = exact.scale
    var magnitude = abs(exact.constant) + abs(offset * scale)
    for (j in 0 until exact.n) magnitude += abs(exact.cost(j) * result.primal[j])
    return (exact.n + 4) * UNIT_ROUNDOFF * magnitude
}

private fun exactResidualGap(exact: FloatEvidenceModel, result: FloatLpResult, duals: DoubleArray): Double? {
    // Only rows whose slack rests on a bound under a nonzero dual add to the gap, so only they need exact activity.
    val counted = BooleanArray(exact.m) { result.basis.status[exact.n + it] != VarStatus.BASIC && duals[it] != 0.0 }
    val activity = Array(exact.m) { BigFraction.ZERO }
    for (j in 0 until exact.n) {
        var needed = false
        exact.forEachInColumn(j) { row, _ -> if (counted[row]) needed = true }
        if (!needed) continue
        val shifted = (BigFraction.ofDouble(result.primal[j]) ?: return null) - exact.model.exactShift(j)
        if (shifted.isZero) continue
        exact.model.forEachRationalColumn(j) { row, coefficient ->
            if (counted[row]) activity[row] += coefficient * shifted
        }
    }
    var gap = 0.0
    for (i in 0 until exact.m) {
        if (!counted[i]) continue
        val slack = nonbasicSlack(exact, result, i) ?: return null
        val residual = magnitudeAbove(activity[i] + slack - exact.model.exactRhs(i)) ?: return null
        // The residual is already rounded up; the factor covers the product.
        gap += abs(duals[i]) * residual * (1 + 2 * UNIT_ROUNDOFF)
    }
    return gap * (1 + exact.m * UNIT_ROUNDOFF)
}

private fun paddedResidualGap(exact: FloatEvidenceModel, result: FloatLpResult, duals: DoubleArray): Double? {
    val activity = DoubleArray(exact.m)
    val magnitude = DoubleArray(exact.m)
    val terms = IntArray(exact.m)
    for (j in 0 until exact.n) {
        val origin = exact.origin(j)
        val shifted = result.primal[j] - origin
        exact.forEachInColumn(j) { row, a ->
            activity[row] += a * shifted
            magnitude[row] += abs(a) * (abs(result.primal[j]) + abs(origin))
            terms[row]++
        }
    }
    var gap = 0.0
    for (i in 0 until exact.m) {
        val slackColumn = exact.n + i
        if (result.basis.status[slackColumn] == VarStatus.BASIC || duals[i] == 0.0) continue
        val slack = nonbasicSlackD(exact, result, i) ?: return null
        val rhs = exact.rhs(i)
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
    exact: FloatEvidenceModel,
    duals: DoubleArray,
    exactDuals: ExactDuals?,
    j: Int,
    status: VarStatus,
    resting: Boolean,
): Double? {
    val reduced = if (exactDuals != null) {
        val (numerator, denominator) = exactDuals.reducedTimesDenominator(checkNotNull(exact.model.exactState).model, j)
        BigFraction.of(numerator, denominator * exactDuals.denominator)
    } else {
        var sum = exact.model.exactCost(j)
        if (j < exact.n) {
            var finite = true
            exact.model.forEachRationalColumn(j) { row, coefficient ->
                val dual = BigFraction.ofDouble(duals[row])
                if (dual == null) finite = false else sum -= coefficient * dual
            }
            if (!finite) return null
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
private class RowView(private val exact: FloatEvidenceModel) {
    private val lower = DoubleArray(exact.numVars) {
        exact.lower(it)
    }
    private val upper = DoubleArray(exact.numVars) {
        exact.upper(it)
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
        for (j in 0 until exact.n) exact.forEachInColumn(j) { row, a -> add(row, j, a) }
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
    fun impliedRange(j: Int): Double = impliedBounds(j).let { max(it.second - it.first, 0.0) }

    /** [j]'s box intersected with the interval every row through it implies, as `(low, high)`. */
    fun impliedBounds(j: Int): Pair<Double, Double> {
        var low = lower[j]
        var high = upper[j]
        if (j >= exact.n) {
            low = max(low, implied(j - exact.n, j, 1.0, lowSide = true))
            high = minOf(high, implied(j - exact.n, j, 1.0, lowSide = false))
        } else {
            exact.forEachInColumn(j) { row, a ->
                low = max(low, implied(row, j, a, lowSide = true))
                high = minOf(high, implied(row, j, a, lowSide = false))
            }
        }
        return low to high
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
        val rhs = exact.rhs(i)
        val bound = (rhs - others) / a
        val widening = (terms[i] + 8) * UNIT_ROUNDOFF * (magnitude + abs(rhs)) / abs(a)
        return if (lowSide) bound - widening else bound + widening
    }

    private fun subnormal(a: Double): Boolean = a != 0.0 && abs(a) < MIN_NORMAL
}

private const val MIN_NORMAL: Double = 2.2250738585072014e-308

// An unshifted, unclamped source projection has the same numeric data as its exact working model. Rounded shifts
// and probe endpoints require importing authority before solving; their projected feasible regions can differ.
internal fun LpModel.floatProjectionAuthoritative(): Boolean {
    if (exactState != null) return true
    if (!finiteExactInput() || probeClampedLo.any { it } || probeClampedHi.any { it }) return false
    val view = doubleView
    if (view != null) {
        val shifts = view.shifts.value
        return shifts.rows.isEmpty() && shifts.columns.isEmpty() &&
            view.exactObjConstant() == exactDouble(view.objConstant)
    }
    val exactRange = -(1L shl 53)..(1L shl 53)
    return csc.colVal.all { it in exactRange } && rhs.all { it in exactRange } &&
        cost.all { it in exactRange } && loShift.all { it in exactRange } &&
        upper.indices.all { !hasUpper[it] || upper[it] in exactRange } && objConstant in exactRange
}

private class FloatEvidenceModel(val model: LpModel) {
    private val source = model.exactState?.model
    val n: Int get() = model.n
    val m: Int get() = model.m
    val numVars: Int get() = model.numVars
    val scale: Double get() = source?.objective?.scale?.approximation ?: 1.0
    val constant: Double get() = source?.objective?.constant?.approximation ?: model.objConstantD

    fun cost(column: Int): Double = source?.objective?.cost(column)?.approximation ?: model.costD(column)
    fun rhs(row: Int): Double = source?.rhs(row)?.approximation ?: model.rhsD(row)
    fun origin(column: Int): Double = source?.column(column)?.origin?.approximation ?: model.loShiftD(column)
    fun fixed(column: Int): Boolean = model.fixed(column)
    // A probe endpoint stands in for an open side.
    fun lower(column: Int): Double = if (model.hasFiniteLower(column) && !probeClamped(column, model.probeClampedLo)) {
        model.lowerD(column)
    } else {
        Double.NEGATIVE_INFINITY
    }
    fun upper(column: Int): Double = source?.let {
        it.column(column).bounds.upper?.number?.approximation ?: Double.POSITIVE_INFINITY
    } ?: if (model.hasFiniteUpper(column) && !probeClamped(column, model.probeClampedHi)) {
        model.upperD(column)
    } else {
        Double.POSITIVE_INFINITY
    }

    private fun probeClamped(column: Int, clamped: BooleanArray): Boolean =
        source == null && column < n && clamped[column]

    inline fun forEachInColumn(column: Int, action: (Int, Double) -> Unit) {
        val exact = model.exactState?.model
        if (exact != null) {
            for (entry in exact.entries(column)) action(entry.row, entry.number.approximation)
        } else {
            model.forEachInColumnD(column, action)
        }
    }
}

/**
 * Lagrangian deduction data read from float duals, for finite-lane deductions accepted numerically
 * ([LpSolveContext.numericalDeductions]). `L(y) = y·rhs + Σⱼ min_{[lⱼ,uⱼ]} dⱼ·zⱼ` bounds the objective for any
 * multipliers; here it is summed in binary64, lowered by that summation's rounding and by an absolute and
 * relative slack, so float noise above an integer never rounds an integral bound up. Every nonzero reduced cost
 * prices and cites its minimizing side; one within [FLOAT_EVIDENCE_TOLERANCE] on an open side is accepted as
 * zero, which with the binary64 evaluation makes the deductions numerical. Reduced costs are in source units.
 */
internal class FloatDeductionCertificate private constructor(
    private val model: LpModel,
    private val duals: DoubleArray,
    private val reduced: DoubleArray,
    /** The source objective bound [exactObjectiveBound] states. */
    val lowerBound: Double,
) : LpDeductionCertificate {
    private val bound by lazy { checkNotNull(BigFraction.ofDouble(lowerBound)) }

    override fun belongsTo(model: LpModel, observer: LpCertificationObserver?): Boolean = model === this.model
    override fun exactMultipliers(): List<BigFraction> = duals.map { checkNotNull(BigFraction.ofDouble(it)) }
    override fun exactMultiplier(row: Int): BigFraction = checkNotNull(BigFraction.ofDouble(duals[row]))
    override fun exactReducedCost(col: Int): BigFraction = checkNotNull(BigFraction.ofDouble(reduced[col]))
    override fun exactObjectiveBound(): BigFraction = bound
    override fun dualNonzeroRow(row: Int): Boolean = duals[row] != 0.0
    override fun reducedCostSign(col: Int): Int = sign(reduced[col]).toInt()
    override fun objectiveBoundCeil(extraConstant: Long): Long? = (bound + BigFraction.ofLong(extraConstant)).ceilLong()
    override fun improvingGapNonNegative(improvingMax: Long, sourceConstant: Long): Boolean =
        gap(improvingMax.toDouble(), sourceConstant) >= 0.0
    override fun rationalFixingWork(col: Int, cutoff: BigFraction): Long = 0L
    override fun permitsMovement(col: Int, steps: Long, cutoff: Long, sourceConstant: Long): Boolean =
        abs(reduced[col]) * steps <= gap(cutoff.toDouble(), sourceConstant)
    override fun permitsMovementWithin(col: Int, steps: Long, gap: BigFraction): Boolean =
        abs(reduced[col]) * steps <= gap.toDouble()
    override fun fixStepsForGap(col: Int, gap: BigFraction): Long? = steps(col, gap.toDouble())
    override fun fixStepsAtCutoff(col: Int, cutoff: BigFraction, sourceConstant: Long): Long? =
        steps(col, gap(cutoff.toDouble(), sourceConstant))
    override fun fixSteps(col: Int, improvingMax: Long, sourceConstant: Long): Long? =
        steps(col, gap(improvingMax.toDouble(), sourceConstant))

    // Widened by the rounding of the conversions and the subtraction, so a fixing never moves past the cutoff.
    private fun gap(cutoff: Double, sourceConstant: Long): Double {
        val constant = sourceConstant.toDouble()
        return cutoff - constant - lowerBound + 4 * UNIT_ROUNDOFF * (abs(cutoff) + abs(constant) + abs(lowerBound))
    }

    private fun steps(col: Int, gap: Double): Long? {
        val cost = abs(reduced[col])
        if (cost == 0.0 || !(gap >= 0.0)) return null
        val steps = floor((gap / cost).nextUp())
        return if (steps < Long.MAX_VALUE.toDouble()) steps.toLong() else null
    }

    companion object {
        /** The certificate [y] supports on [model], or null when a non-negligible reduced cost meets an open side. */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun of(model: LpModel, y: DoubleArray): FloatDeductionCertificate? {
            if (y.size != model.m || !model.finiteExactInput()) return null
            val view = FloatEvidenceModel(model)
            val scale = view.scale
            if (!(scale > 0.0) || !scale.isFinite()) return null
            val duals = repairedDuals(view, y) ?: return null
            var value = view.constant
            var magnitude = abs(value)
            for (i in 0 until view.m) {
                value += duals[i] * view.rhs(i)
                magnitude += abs(duals[i] * view.rhs(i))
            }
            var margin = 0.0
            val reduced = DoubleArray(view.numVars)
            for (j in 0 until view.numVars) {
                val (d, error) = reducedCost(view, duals, j)
                if (d == 0.0) continue
                val side = if (d > 0.0) view.lower(j) else view.upper(j)
                if (!side.isFinite()) {
                    if (abs(d) <= FLOAT_EVIDENCE_TOLERANCE) continue
                    return null
                }
                value += d * side
                magnitude += abs(d * side)
                margin += error * abs(side)
                reduced[j] = d / scale
            }
            margin += (view.numVars + view.m + 4) * UNIT_ROUNDOFF * magnitude
            val source = model.objectiveD(value - margin)
            val lowerBound = source - 2 * UNIT_ROUNDOFF * abs(source) -
                INTEGRAL_SLACK - INTEGRAL_SLACK_RELATIVE * abs(source)
            return if (lowerBound.isFinite()) FloatDeductionCertificate(model, duals, reduced, lowerBound) else null
        }
    }
}

// Duals at noise level are dropped, and a one-sided slack's dual moves to its cost so the slack prices nothing:
// any multipliers bound the objective, and these keep the bound finite and the cited rows few.
private fun repairedDuals(view: FloatEvidenceModel, y: DoubleArray): DoubleArray? {
    var largest = 0.0
    for (value in y) {
        if (!value.isFinite()) return null
        largest = max(largest, abs(value))
    }
    return DoubleArray(view.m) { i ->
        val slack = view.n + i
        val cost = view.cost(slack)
        val dual = if (abs(y[i]) <= NEGLIGIBLE_DUAL * largest) 0.0 else y[i]
        val open = (view.lower(slack) == Double.NEGATIVE_INFINITY && dual < cost) ||
            (view.upper(slack) == Double.POSITIVE_INFINITY && dual > cost)
        if (open) cost else dual
    }
}

// Column j's reduced cost under [y], with the rounding its summation can carry.
private fun reducedCost(view: FloatEvidenceModel, y: DoubleArray, j: Int): Pair<Double, Double> {
    var d = view.cost(j)
    var magnitude = abs(d)
    var terms = 1
    if (j >= view.n) {
        d -= y[j - view.n]
        magnitude += abs(y[j - view.n])
        terms++
    } else {
        view.forEachInColumn(j) { row, a ->
            d -= y[row] * a
            magnitude += abs(y[row] * a)
            terms++
        }
    }
    return d to (terms + 4) * UNIT_ROUNDOFF * magnitude
}

/**
 * [ray] oriented and rounded to integer multipliers when, summed in binary64, it shows the rows of [model] cannot
 * hold inside its box: `ρ·rhs > Σⱼ max(ρ·Aⱼ·zⱼ)` by [FLOAT_EVIDENCE_TOLERANCE] times the terms' size. An open side
 * that the float ray meets is priced at the bound the other rows imply. The rounded ray is checked again against the
 * box alone, the sides an infeasibility clause cites. The numerical counterpart of [certifyLpFarkas].
 */
@Suppress("ReturnCount")
internal fun floatFarkasRay(model: LpModel, ray: DoubleArray): LongArray? {
    if (ray.size != model.m || !model.finiteExactInput()) return null
    var largest = 0.0
    for (value in ray) {
        if (!value.isFinite()) return null
        largest = max(largest, abs(value))
    }
    if (largest == 0.0) return null
    val view = FloatEvidenceModel(model)
    val rows = lazy { RowView(view) }
    val normalized = DoubleArray(ray.size) { ray[it] / largest }
    val sign = farkasSign(view, normalized, rows) ?: return null
    val rounded = roundDuals(model, DoubleArray(ray.size) { sign * normalized[it] })?.mult ?: return null
    return rounded.takeIf { farkasSign(view, DoubleArray(it.size) { row -> it[row].toDouble() }, null) == 1 }
}

// +1 when ρ·rhs exceeds every ρ·A·z in the box by the margin, -1 when it falls below every one, else null. Open
// sides take their implied bounds from [rows] when given and refute nothing otherwise.
@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun farkasSign(view: FloatEvidenceModel, ray: DoubleArray, rows: Lazy<RowView>?): Int? {
    var activity = 0.0
    var magnitude = 0.0
    for (i in 0 until view.m) {
        activity += ray[i] * view.rhs(i)
        magnitude += abs(ray[i] * view.rhs(i))
    }
    var most = 0.0
    var least = 0.0
    for (j in 0 until view.numVars) {
        var r = 0.0
        if (j >= view.n) r = ray[j - view.n] else view.forEachInColumn(j) { row, a -> r += ray[row] * a }
        if (r == 0.0) continue
        var lower = view.lower(j)
        var upper = view.upper(j)
        if (rows != null && (!lower.isFinite() || !upper.isFinite())) {
            val (low, high) = rows.value.impliedBounds(j)
            lower = low
            upper = high
        }
        val high = r * (if (r > 0.0) upper else lower)
        val low = r * (if (r > 0.0) lower else upper)
        most += high
        least += low
        magnitude += max(if (high.isFinite()) abs(high) else 0.0, if (low.isFinite()) abs(low) else 0.0)
    }
    val margin = FLOAT_EVIDENCE_TOLERANCE * magnitude
    return when {
        most.isFinite() && activity - most > margin -> 1
        least.isFinite() && least - activity > margin -> -1
        else -> null
    }
}

// Slack below a numerical bound before any integral rounding: float noise of this size is not an objective step.
private const val INTEGRAL_SLACK: Double = 1e-6
private const val INTEGRAL_SLACK_RELATIVE: Double = 1e-9

// Duals this far below the largest carry no information a binary64 basis solve can resolve.
private const val NEGLIGIBLE_DUAL: Double = 1e-15
