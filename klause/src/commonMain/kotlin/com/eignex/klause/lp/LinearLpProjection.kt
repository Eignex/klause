package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.linearRows
import com.ionspin.kotlin.bignum.integer.BigInteger
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

internal fun Linear.emitLpRelaxation(builder: RelaxationBuilder, projection: LinearLpProjection? = null) {
    for (row in linearRows) row.emitLpRelaxation(builder, projection)
}

/** Emit a wide row as directionally-rounded double outer-relaxation rows. */
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

/**
 * Which side of zero the model confines each column to, or null when the row cannot be relaxed at all.
 *
 * A coefficient rounds outward on the side its column sits, so the direction is what the row's validity
 * rests on — and a column the model states nothing about on the relevant side has no direction. Such a
 * column would otherwise enter split over its root box, capping it at an endpoint the model never stated;
 * an outer relaxation has no weaker form to fall back on, so the row is declined instead.
 */
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

/** Per column of a wide row: the side of zero the model confines it to, and the `x = x⁺ − x⁻` pair a
 *  straddling column is split into — the parts' upper bounds up front, their column handles once
 *  [seat] has created them. */
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

    /** Record the `x⁺` / `x⁻` column handles created for straddling column [i]. */
    fun seat(i: Int, plus: Int, minus: Int) {
        plusCol[i] = plus
        minusCol[i] = minus
    }

    fun plus(i: Int): Int = plusCol[i]

    fun minus(i: Int): Int = minusCol[i]

    /** Whether column [i]'s coefficient rounds down on a row read in direction [ge]. */
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
    builder.realRow(cols, dcoeffs, if (ge) LinearOp.GE else LinearOp.LE, rhs, strict = false)
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

private fun fitsDouble(x: BigInteger): Boolean {
    val bits = x.bitLength()
    return when {
        bits <= DOUBLE_CERTAIN_FINITE_BITS -> true
        bits > DOUBLE_CERTAIN_FINITE_BITS + 1 -> false
        else -> x.doubleValue(exactRequired = false).isFinite()
    }
}

private fun floorToDouble(x: BigInteger): Double {
    val d = x.doubleValue(exactRequired = false)
    return if (BigInteger.tryFromDouble(d, exactRequired = false) > x) d.nextDown() else d
}

private fun ceilToDouble(x: BigInteger): Double {
    val d = x.doubleValue(exactRequired = false)
    return if (BigInteger.tryFromDouble(d, exactRequired = false) < x) d.nextUp() else d
}
