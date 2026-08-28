package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.IntegerConstants
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealConstants
import com.eignex.klause.factor.arithmetic.WideConstants
import com.eignex.klause.ir.LinearOp
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.nextDown
import kotlin.math.nextUp

internal class LinearLpProjection {
    private val wideRoundings = HashMap<Linear, WideRoundingResult>()

    private fun wideRounding(linear: Linear, constants: WideConstants): WideRounding? =
        wideRoundings.getOrPut(linear) { WideRoundingResult(computeWideRounding(constants)) }.rounding

    internal fun emitWide(linear: Linear, builder: RelaxationBuilder, constants: WideConstants) {
        if (linear.op != LinearOp.LE && linear.op != LinearOp.EQ) return
        linear.emitWideOuterRows(builder, wideRounding(linear, constants))
    }
}

internal fun Linear.emitLpRelaxation(builder: RelaxationBuilder, projection: LinearLpProjection? = null) {
    when (val c = constants) {
        is WideConstants -> if (projection == null) {
            if (op == LinearOp.LE || op == LinearOp.EQ) emitWideOuterRows(builder, computeWideRounding(c))
        } else {
            projection.emitWide(this, builder, c)
        }

        is IntegerConstants -> builder.linearRow(op, vars, c.coeffs, c.bound)

        is RealConstants -> {
            val cols = IntArray(vars.size + realVars.size)
            val dcoeffs = DoubleArray(cols.size)
            for (i in vars.indices) {
                cols[i] = builder.intColumn(vars[i])
                dcoeffs[i] = c.intCoefficients.at(i)
            }
            for (j in realVars.indices) {
                cols[vars.size + j] = builder.realColumn(realVars[j])
                dcoeffs[vars.size + j] = c.realCoefficients.at(j)
            }
            builder.realRow(cols, dcoeffs, op, c.bound, c.strict)
        }
    }
}

/** Emit a wide row as directionally-rounded double outer-relaxation rows. */
private fun Linear.emitWideOuterRows(builder: RelaxationBuilder, rounded: WideRounding?) {
    rounded ?: return
    for (i in vars.indices) {
        val dom = builder.declaredDomain(vars[i])
        if (dom.min < 0L && dom.max > 0L && dom.min == Long.MIN_VALUE) return
    }
    val plusCol = IntArray(vars.size) { -1 }
    val minusCol = IntArray(vars.size) { -1 }
    for (i in vars.indices) {
        val dom = builder.declaredDomain(vars[i])
        if (dom.min < 0L && dom.max > 0L) {
            val cp = builder.auxColumn(0L, dom.max)
            val cm = builder.auxColumn(0L, -dom.min)
            builder.realRow(
                intArrayOf(builder.intColumn(vars[i]), cp, cm),
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

private fun Linear.emitWideOuterRow(
    builder: RelaxationBuilder,
    rounded: WideRounding,
    ge: Boolean,
    plusCol: IntArray,
    minusCol: IntArray,
) {
    var straddle = 0
    for (i in vars.indices) if (plusCol[i] >= 0) straddle++
    val cols = IntArray(vars.size + straddle)
    val dcoeffs = DoubleArray(cols.size)
    var w = 0
    for (i in vars.indices) {
        if (plusCol[i] >= 0) {
            cols[w] = plusCol[i]
            dcoeffs[w] = if (ge) rounded.ceilCoeffs[i] else rounded.floorCoeffs[i]
            w++
            cols[w] = minusCol[i]
            dcoeffs[w] = if (ge) -rounded.floorCoeffs[i] else -rounded.ceilCoeffs[i]
            w++
        } else {
            val dom = builder.declaredDomain(vars[i])
            val roundDown = (dom.min >= 0L) != ge
            cols[w] = builder.intColumn(vars[i])
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

private fun computeWideRounding(constants: WideConstants): WideRounding? {
    val exactBound = constants.bound
    val exactCoeffs = constants.coefficients.toTypedArray()
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
