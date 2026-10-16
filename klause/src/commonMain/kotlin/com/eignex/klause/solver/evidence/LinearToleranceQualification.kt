package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.abs
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus

internal class ToleranceLinearRow(
    val terms: List<Pair<Int, BigFraction>>,
    val lower: BigFraction?,
    val upper: BigFraction?,
    val pins: Map<Int, BigFraction>,
)

private class RowLattice(val step: BigFraction, val maximumMagnitude: BigFraction?, val absoluteActivity: Boolean)

internal class LinearToleranceQualification(
    private val integers: List<Boolean>,
    private val variableBounds: List<Pair<BigFraction?, BigFraction?>>,
    private val rows: List<ToleranceLinearRow>,
    private val objective: List<Pair<Int, BigFraction>>,
    private val objectiveConstant: BigFraction,
    private val minimize: Boolean,
    tolerance: SourceTolerance,
    private val cancellation: Cancellation,
) {
    private val absolute = BigFraction.of(tolerance.absolute.numerator, tolerance.absolute.denominator)
    private val relative = BigFraction.of(tolerance.relative.numerator, tolerance.relative.denominator)
    private var interrupted = false
    private var emptyAcceptedSet = false

    fun verify(): Boolean {
        val ranges = ranges()
        if (interrupted) return false
        if (emptyAcceptedSet) return !stopped()
        if (variableBounds.indices.any { index ->
                val (low, high) = ranges[index]
                val (nominalLow, nominalHigh) = variableBounds[index]
                (nominalLow != null && (low == null || low < lowerPoint(nominalLow, integers[index])!!)) ||
                    (nominalHigh != null && (high == null || high > upperPoint(nominalHigh, integers[index])!!))
            }
        ) return false
        return rows.indices.all { rowUnchanged(it, ranges) } && !stopped()
    }

    fun objectiveBound(): BigFraction? {
        val ranges = ranges()
        if (interrupted || emptyAcceptedSet) return null
        var value = objectiveConstant
        for ((column, coefficient) in objective) {
            if (stopped()) return null
            if (coefficient.isZero) continue
            val (low, high) = ranges[column]
            val lowerSide = (coefficient.signum() > 0) == minimize
            val endpoint = (if (lowerSide) low else high) ?: return null
            value += coefficient * endpoint
        }
        return value.takeUnless { stopped() }
    }

    private fun stopped(): Boolean {
        if (!interrupted) {
            cancellation.charge(1)
            interrupted = cancellation()
        }
        return interrupted
    }

    private fun slack(bound: BigFraction, magnitude: BigFraction = BigFraction.ZERO): BigFraction =
        larger(absolute, relative * larger(bound.absolute(), magnitude))

    private fun ranges(): List<Pair<BigFraction?, BigFraction?>> {
        val ranges = variableBounds.mapIndexed { index, (lower, upper) ->
            if (stopped()) return emptyList()
            lowerPoint(lower?.let { it - slack(it) }, integers[index]) to
                upperPoint(upper?.let { it + slack(it) }, integers[index])
        }.toMutableList()
        var changed: Boolean
        do {
            changed = false
            for (row in rows) {
                if (stopped()) return emptyList()
                if (!row.pins.all { (column, value) -> ranges[column] == (value to value) }) continue
                val terms = row.terms.filter { !it.second.isZero }
                if (terms.isEmpty()) {
                    if (row.lower?.let { BigFraction.ZERO < it - slack(it) } == true ||
                        row.upper?.let { BigFraction.ZERO > it + slack(it) } == true
                    ) emptyAcceptedSet = true
                } else if (terms.size == 1 && relative < BigFraction.ONE) {
                    val (column, coefficient) = terms.single()
                    val lower = row.lower?.let { acceptedLower(it) / coefficient }
                    val upper = row.upper?.let { acceptedLower(it.negated()).negated() / coefficient }
                    val (lo, hi) = if (coefficient.signum() > 0) lower to upper else upper to lower
                    val (beforeLow, beforeHigh) = ranges[column]
                    val afterLow = lowerPoint(lo, integers[column])?.let {
                        if (beforeLow == null) it else larger(it, beforeLow)
                    } ?: beforeLow
                    val afterHigh = upperPoint(hi, integers[column])?.let {
                        if (beforeHigh == null) it else smaller(it, beforeHigh)
                    } ?: beforeHigh
                    val after = afterLow to afterHigh
                    if (after != ranges[column]) {
                        ranges[column] = after
                        changed = true
                    }
                }
            }
            if (ranges.any { (lo, hi) -> lo != null && hi != null && lo > hi }) emptyAcceptedSet = true
        } while (changed && !emptyAcceptedSet)
        return ranges
    }

    // For one term, sum(abs(a(i) * x(i))) equals abs(activity); solve each slack branch exactly.
    private fun acceptedLower(bound: BigFraction): BigFraction = smaller(
        bound - absolute,
        bound - relative * bound.absolute(),
        bound / (if (bound.signum() >= 0) BigFraction.ONE + relative else BigFraction.ONE - relative),
    )

    private fun lowerPoint(value: BigFraction?, integer: Boolean): BigFraction? = if (integer) {
        value?.let { BigFraction.of(it.ceilInteger(), BIG_ONE) }
    } else value

    private fun upperPoint(value: BigFraction?, integer: Boolean): BigFraction? = if (integer) {
        value?.let { BigFraction.of(-it.negated().ceilInteger(), BIG_ONE) }
    } else value

    private fun rowUnchanged(index: Int, ranges: List<Pair<BigFraction?, BigFraction?>>): Boolean {
        if (stopped()) return false
        val row = rows[index]
        if (row.pins.any { (column, value) ->
                val (low, high) = ranges[column]
                (low != null && value < low) || (high != null && value > high)
            }
        ) return true
        val lo = row.lower
        val hi = row.upper
        if (lo == null && hi == null) return true
        val coefficients = row.terms.map { it.second }
        if (coefficients.all { it.isZero }) {
            val nominal = (lo == null || BigFraction.ZERO >= lo) && (hi == null || BigFraction.ZERO <= hi)
            val accepted = (lo == null || BigFraction.ZERO >= lo - slack(lo)) &&
                (hi == null || BigFraction.ZERO <= hi + slack(hi))
            return nominal == accepted
        }
        val finiteActivity = activityRange(row, ranges)
        if (finiteActivity != null) {
            val (minimum, maximum) = finiteActivity
            if ((lo == null || minimum >= lo) && (hi == null || maximum <= hi)) return true
        }
        val lattice = lattice(index, coefficients, ranges) ?: return false
        val step = lattice.step
        fun permittedSlack(bound: BigFraction, activity: BigFraction): BigFraction? {
            val magnitude = lattice.maximumMagnitude ?: if (lattice.absoluteActivity && relative < BigFraction.ONE) {
                activity.absolute()
            } else return null
            return slack(bound, magnitude)
        }
        // Slack below the first excluded lattice point cannot admit a nominally invalid integer activity.
        if (lo != null) {
            val below = BigFraction.of((lo / step).ceilInteger(), BIG_ONE) - BigFraction.ONE
            if ((permittedSlack(lo, below * step) ?: return false) >= lo - below * step) return false
        }
        if (hi != null) {
            val above = BigFraction.of(-(hi / step).negated().ceilInteger(), BIG_ONE) + BigFraction.ONE
            if ((permittedSlack(hi, above * step) ?: return false) >= above * step - hi) return false
        }
        return true
    }

    private fun activityRange(
        row: ToleranceLinearRow,
        ranges: List<Pair<BigFraction?, BigFraction?>>,
    ): Pair<BigFraction, BigFraction>? {
        var minimum = BigFraction.ZERO
        var maximum = BigFraction.ZERO
        for ((column, coefficient) in row.terms) {
            if (stopped()) return null
            if (coefficient.isZero) continue
            val (low, high) = ranges[column]
            if (low == null || high == null) return null
            val first = coefficient * low
            val last = coefficient * high
            minimum += smaller(first, last)
            maximum += larger(first, last)
        }
        return minimum to maximum
    }

    private fun lattice(
        index: Int,
        coefficients: List<BigFraction>,
        ranges: List<Pair<BigFraction?, BigFraction?>>,
    ): RowLattice? {
        var denominator = BIG_ONE
        var magnitude: BigFraction? = BigFraction.ZERO
        var nonnegative = true
        var nonpositive = true
        for (entry in coefficients.indices) {
            if (stopped()) return null
            val coefficient = coefficients[entry]
            if (coefficient.isZero) continue
            val column = rows[index].terms[entry].first
            if (!integers[column]) return null
            denominator = denominator / denominator.gcd(coefficient.den) * coefficient.den
            if (!relative.isZero) {
                val (low, high) = ranges[column]
                val positive = coefficient.signum() > 0
                nonnegative = nonnegative && (if (positive) low?.signum()?.let { it >= 0 } else
                    high?.signum()?.let { it <= 0 }) == true
                nonpositive = nonpositive && (if (positive) high?.signum()?.let { it <= 0 } else
                    low?.signum()?.let { it >= 0 }) == true
                magnitude = if (low == null || high == null) null else
                    magnitude?.plus(coefficient.absolute() * larger(low.absolute(), high.absolute()))
            }
        }
        var divisor = BIG_ZERO
        for (coefficient in coefficients) {
            if (stopped()) return null
            divisor = divisor.gcd((coefficient * BigFraction.of(denominator, BIG_ONE)).num.abs())
        }
        return RowLattice(BigFraction.of(divisor, denominator), magnitude,
            coefficients.count { !it.isZero } == 1 || nonnegative || nonpositive)
    }
}

private fun BigFraction.absolute(): BigFraction = if (signum() < 0) negated() else this

private operator fun BigFraction.div(other: BigFraction): BigFraction = this * other.reciprocal()

private fun larger(first: BigFraction, second: BigFraction): BigFraction = if (first >= second) first else second

private fun smaller(first: BigFraction, second: BigFraction): BigFraction = if (first <= second) first else second

private fun smaller(first: BigFraction, second: BigFraction, third: BigFraction): BigFraction =
    smaller(smaller(first, second), third)
