package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.linearRow
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.nextDown
import kotlin.math.nextUp

internal class LinearLpProjection {
    private val wideRoundings = HashMap<Factor, WideRoundingResult>()

    internal val cachedWideRoundingCount: Int get() = wideRoundings.size

    private fun wideRounding(source: Factor, row: FactorRow.Wide): WideRounding? =
        wideRoundings.getOrPut(source) { WideRoundingResult(computeWideRounding(row)) }.rounding

    internal fun emitWide(source: Factor, row: FactorRow.Wide, builder: RelaxationBuilder) {
        if (row.op != LinearOp.LE && row.op != LinearOp.EQ) return
        row.emitWideOuterRows(builder, wideRounding(source, row))
    }
}

internal fun Linear.emitLpRelaxation(builder: RelaxationBuilder, projection: LinearLpProjection? = null) {
    linearRow()?.emitLpRelaxation(builder, projection, this)
}

/** Emit a wide row as directionally-rounded double outer-relaxation rows. */
internal fun FactorRow.Wide.emitWideLpRelaxation(builder: RelaxationBuilder) =
    emitWideOuterRows(builder, computeWideRounding(this))

private fun FactorRow.Wide.emitWideOuterRows(builder: RelaxationBuilder, rounded: WideRounding?) {
    rounded ?: return
    val split = splitColumns(builder) ?: return
    for (i in intVars.indices) {
        if (!split.straddles(i)) continue
        val dom = builder.rootDomain(intVars[i])
        val cp = builder.auxColumn(0L, dom.max)
        val cm = builder.auxColumn(0L, -dom.min)
        builder.realRow(
            intArrayOf(builder.intColumn(intVars[i]), cp, cm),
            doubleArrayOf(1.0, -1.0, 1.0),
            LinearOp.EQ,
            0.0,
            strict = false,
        )
        split.plusCol[i] = cp
        split.minusCol[i] = cm
    }
    emitWideOuterRow(builder, rounded, ge = false, split)
    if (op == LinearOp.EQ) emitWideOuterRow(builder, rounded, ge = true, split)
}

/**
 * Which side of zero the model confines each column to, or null when the row cannot be relaxed at all.
 *
 * A coefficient rounds outward on the side its column sits, so the direction is what the row's validity
 * rests on — and a column the model states nothing about on the relevant side has no direction. Such a
 * column would otherwise enter split over its root box, capping it at an endpoint the model never stated;
 * an outer relaxation has no weaker form to fall back on, so the row is declined instead.
 */
private fun FactorRow.Wide.splitColumns(builder: RelaxationBuilder): WideSplit? {
    val nonNegative = BooleanArray(intVars.size)
    val straddling = BooleanArray(intVars.size)
    for (i in intVars.indices) {
        val v = intVars[i]
        val dom = builder.rootDomain(v)
        nonNegative[i] = builder.statesLowerBound(v) && dom.min >= 0L
        if (nonNegative[i] || (builder.statesUpperBound(v) && dom.max <= 0L)) continue
        if (!builder.statesBothBounds(v)) return null
        if (dom.min == Long.MIN_VALUE) return null // the negative part's upper bound would overflow
        straddling[i] = true
    }
    return WideSplit(nonNegative, straddling)
}

/** Per column of a wide row: the side of zero the model confines it to, and the `x = x⁺ − x⁻` pair a
 *  straddling column was split into (`-1` until the pair is created). */
private class WideSplit(private val nonNegative: BooleanArray, private val straddling: BooleanArray) {
    val plusCol = IntArray(nonNegative.size) { -1 }
    val minusCol = IntArray(nonNegative.size) { -1 }

    fun straddles(i: Int): Boolean = straddling[i]

    /** Whether column [i]'s coefficient rounds down on a row read in direction [ge]. */
    fun roundsDown(i: Int, ge: Boolean): Boolean = nonNegative[i] != ge
}

private fun FactorRow.Wide.emitWideOuterRow(
    builder: RelaxationBuilder,
    rounded: WideRounding,
    ge: Boolean,
    split: WideSplit,
) {
    var straddle = 0
    for (i in intVars.indices) if (split.plusCol[i] >= 0) straddle++
    val cols = IntArray(intVars.size + straddle)
    val dcoeffs = DoubleArray(cols.size)
    var w = 0
    for (i in intVars.indices) {
        if (split.plusCol[i] >= 0) {
            cols[w] = split.plusCol[i]
            dcoeffs[w] = if (ge) rounded.ceilCoeffs[i] else rounded.floorCoeffs[i]
            w++
            cols[w] = split.minusCol[i]
            dcoeffs[w] = if (ge) -rounded.floorCoeffs[i] else -rounded.ceilCoeffs[i]
            w++
        } else {
            cols[w] = builder.intColumn(intVars[i])
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

private fun computeWideRounding(row: FactorRow.Wide): WideRounding? {
    val exactBound = row.bound
    val exactCoeffs = row.coefficients
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
