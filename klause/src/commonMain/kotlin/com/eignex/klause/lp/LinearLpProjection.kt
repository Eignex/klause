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
    for (i in intVars.indices) {
        val dom = builder.declaredDomain(intVars[i])
        if (dom.min < 0L && dom.max > 0L && dom.min == Long.MIN_VALUE) return
    }
    val plusCol = IntArray(intVars.size) { -1 }
    val minusCol = IntArray(intVars.size) { -1 }
    for (i in intVars.indices) {
        val dom = builder.declaredDomain(intVars[i])
        if (dom.min < 0L && dom.max > 0L) {
            val cp = builder.auxColumn(0L, dom.max)
            val cm = builder.auxColumn(0L, -dom.min)
            builder.realRow(
                intArrayOf(builder.intColumn(intVars[i]), cp, cm),
                doubleArrayOf(1.0, -1.0, 1.0),
                LinearOp.EQ,
                0.0,
                strict = false,
            )
            plusCol[i] = cp
            minusCol[i] = cm
        }
    }
    emitWideOuterRow(builder, rounded, ge = false, plusCol, minusCol)
    if (op == LinearOp.EQ) emitWideOuterRow(builder, rounded, ge = true, plusCol, minusCol)
}

private fun FactorRow.Wide.emitWideOuterRow(
    builder: RelaxationBuilder,
    rounded: WideRounding,
    ge: Boolean,
    plusCol: IntArray,
    minusCol: IntArray,
) {
    var straddle = 0
    for (i in intVars.indices) if (plusCol[i] >= 0) straddle++
    val cols = IntArray(intVars.size + straddle)
    val dcoeffs = DoubleArray(cols.size)
    var w = 0
    for (i in intVars.indices) {
        if (plusCol[i] >= 0) {
            cols[w] = plusCol[i]
            dcoeffs[w] = if (ge) rounded.ceilCoeffs[i] else rounded.floorCoeffs[i]
            w++
            cols[w] = minusCol[i]
            dcoeffs[w] = if (ge) -rounded.floorCoeffs[i] else -rounded.ceilCoeffs[i]
            w++
        } else {
            val dom = builder.declaredDomain(intVars[i])
            val roundDown = (dom.min >= 0L) != ge
            cols[w] = builder.intColumn(intVars[i])
            dcoeffs[w] = if (roundDown) rounded.floorCoeffs[i] else rounded.ceilCoeffs[i]
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
