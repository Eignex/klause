package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.klause.lp.engine.scaled
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.floorBigInt
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.toDouble
import kotlin.math.max
import kotlin.math.nextDown
import kotlin.math.nextUp

internal class LinearLpProjection {
    private val wideRoundings = HashMap<LinearRow, WideRoundingResult>()

    internal val cachedWideRoundingCount: Int get() = wideRoundings.size

    private fun wideRounding(row: LinearRow): WideRounding? =
        wideRoundings.getOrPut(row) { WideRoundingResult(computeWideRounding(row)) }.rounding

    internal fun emitWide(row: LinearRow, builder: RelaxationBuilder) {
        if (row.relation != LinearOp.LE && row.relation != LinearOp.EQ) return
        row.emitWideOuterRows(builder, wideRounding(row))
    }
}

internal fun Linear.emitLpRelaxation(
    builder: RelaxationBuilder, projection: LinearLpProjection? = null, slack: LpRowSlack? = null,
) {
    for (row in linearRows) row.emitLpRelaxation(builder, projection, slack)
}

internal fun LinearRow.emitWideLpRelaxation(builder: RelaxationBuilder) =
    emitWideOuterRows(builder, computeWideRounding(this))

private fun LinearRow.emitWideOuterRows(builder: RelaxationBuilder, rounded: WideRounding?) {
    val variables = intVars
    rounded ?: return
    val split = splitColumns(builder) ?: return
    for (i in variables.indices) {
        if (!split.straddles(i)) continue
        val cp = builder.auxColumn(0L, split.plusUpper(i))
        val cm = builder.auxColumn(0L, split.minusUpper(i))
        builder.realRow(
            intArrayOf(builder.intColumn(variables[i]), cp, cm),
            doubleArrayOf(1.0, -1.0, 1.0),
            LinearOp.EQ,
            0.0,
            strict = false,
        )
        split.seat(i, cp, cm)
    }
    emitWideOuterRow(builder, rounded, ge = false, split)
    if (relation == LinearOp.EQ) emitWideOuterRow(builder, rounded, ge = true, split)
}

// A coefficient rounds outward on the side of zero its column sits, so a column the model leaves open on
// that side has no direction. Splitting it over its root box would cap it at an unstated endpoint, so the
// row is declined (null).
private fun LinearRow.splitColumns(builder: RelaxationBuilder): WideSplit? {
    val variables = intVars
    val nonNegative = BooleanArray(variables.size)
    val straddling = BooleanArray(variables.size)
    val plusUpper = LongArray(variables.size)
    val minusUpper = LongArray(variables.size)
    for (i in variables.indices) {
        val v = variables[i]
        val dom = builder.rootDomain(v)
        nonNegative[i] = builder.statesLowerBound(v) && dom.min >= 0L
        if (nonNegative[i] || (builder.statesUpperBound(v) && dom.max <= 0L)) continue
        if (!builder.statesBothBounds(v)) return null
        if (dom.min == Long.MIN_VALUE) return null // the negative part's upper bound would overflow
        straddling[i] = true
        plusUpper[i] = dom.max
        minusUpper[i] = -dom.min
    }
    return WideSplit(nonNegative, straddling, plusUpper, minusUpper)
}

// A straddling column is split as `x = x⁺ − x⁻`; the part handles exist only after [seat].
private class WideSplit(
    private val nonNegative: BooleanArray,
    private val straddling: BooleanArray,
    private val plusUpper: LongArray,
    private val minusUpper: LongArray,
) {
    private val plusCol = IntArray(nonNegative.size) { -1 }
    private val minusCol = IntArray(nonNegative.size) { -1 }

    fun straddles(i: Int): Boolean = straddling[i]

    fun plusUpper(i: Int): Long = plusUpper[i]

    fun minusUpper(i: Int): Long = minusUpper[i]

    fun seat(i: Int, plus: Int, minus: Int) {
        plusCol[i] = plus
        minusCol[i] = minus
    }

    fun plus(i: Int): Int = plusCol[i]

    fun minus(i: Int): Int = minusCol[i]

    fun isNonNegative(i: Int): Boolean = nonNegative[i]

    fun roundsDown(i: Int, ge: Boolean): Boolean = nonNegative[i] != ge
}

private fun LinearRow.emitWideOuterRow(
    builder: RelaxationBuilder,
    rounded: WideRounding,
    ge: Boolean,
    split: WideSplit,
) {
    val variables = intVars
    var straddle = 0
    for (i in variables.indices) if (split.straddles(i)) straddle++
    val cols = IntArray(variables.size + straddle)
    val dcoeffs = DoubleArray(cols.size)
    var w = 0
    for (i in variables.indices) {
        if (split.straddles(i)) {
            cols[w] = split.plus(i)
            dcoeffs[w] = if (ge) rounded.ceilCoeffs[i] else rounded.floorCoeffs[i]
            w++
            cols[w] = split.minus(i)
            dcoeffs[w] = if (ge) -rounded.floorCoeffs[i] else -rounded.ceilCoeffs[i]
            w++
        } else {
            cols[w] = builder.intColumn(variables[i])
            dcoeffs[w] = if (split.roundsDown(i, ge)) rounded.floorCoeffs[i] else rounded.ceilCoeffs[i]
            w++
        }
    }
    val rhs = if (ge) rounded.floorBound else rounded.ceilBound
    val op = if (ge) LinearOp.GE else LinearOp.LE
    val slack = wideRowSlack(builder, rounded, ge, split)
    if (slack == null) builder.realRow(cols, dcoeffs, op, rhs, strict = false)
    else builder.derivedRealRow(cols, dcoeffs, op, rhs, slack)
}

private fun LinearRow.wideRowSlack(
    builder: RelaxationBuilder, rounded: WideRounding, ge: Boolean, split: WideSplit,
): LpRowSlack? {
    val source = builder as? SourceRowSlackBuilder ?: return null
    val original = source.sourceRowSlack(this, includeAbsolute = true) ?: return null
    var gapWeight = 0.0
    var boundMagnitude = 0.0
    val coefficients = wideCoefficients
    val variables = intVars
    for (index in variables.indices) {
        if (split.straddles(index)) continue
        val coefficient = if (split.roundsDown(index, ge)) rounded.floorCoeffs[index] else rounded.ceilCoeffs[index]
        val difference = checkNotNull(BigFraction.ofDouble(coefficient)) - BigFraction.of(coefficients[index], BIG_ONE)
        val gap = if (difference < BigFraction.ZERO) difference.negated() else difference
        if (gap.isZero) continue
        gapWeight = (gapWeight + gap.toDouble().nextUp()).nextUp()
        val magnitude = source.sourceSignBoundMagnitude(variables[index], upper = !split.isNonNegative(index))
            ?: return original.scaled(Double.POSITIVE_INFINITY)
        boundMagnitude = max(boundMagnitude, magnitude)
    }
    if (gapWeight == 0.0) return original
    // A tolerance crossing of a declared sign bound reverses the coefficient's outward rounding direction.
    return LpRowSlack(DoubleArray(original.size + 1) {
        if (it == original.size) gapWeight else original.multiplier(it)
    }, DoubleArray(original.size + 1) {
        if (it == original.size) boundMagnitude else original.magnitude(it)
    })
}

private class WideRoundingResult(val rounding: WideRounding?)

private class WideRounding(
    val floorCoeffs: DoubleArray,
    val ceilCoeffs: DoubleArray,
    val floorBound: Double,
    val ceilBound: Double,
)

private fun computeWideRounding(row: LinearRow): WideRounding? {
    val exactBound = row.wideBound
    val exactCoeffs = row.wideCoefficients
    if (!fitsDouble(exactBound) || !exactCoeffs.all { fitsDouble(it) }) return null
    return WideRounding(
        DoubleArray(exactCoeffs.size) { floorToDouble(exactCoeffs[it]) },
        DoubleArray(exactCoeffs.size) { ceilToDouble(exactCoeffs[it]) },
        floorToDouble(exactBound),
        ceilToDouble(exactBound),
    )
}

private const val DOUBLE_CERTAIN_FINITE_BITS = 1023

private fun fitsDouble(x: BigInt): Boolean {
    val bits = x.magnitudeBitLength()
    return when {
        bits <= DOUBLE_CERTAIN_FINITE_BITS -> true
        bits > DOUBLE_CERTAIN_FINITE_BITS + 1 -> false
        else -> x.toDouble().isFinite()
    }
}

private fun floorToDouble(x: BigInt): Double {
    val d = x.toDouble()
    return if (floorBigInt(d) > x) d.nextDown() else d
}

private fun ceilToDouble(x: BigInt): Double {
    val d = x.toDouble()
    return if (floorBigInt(d) < x) d.nextUp() else d
}
