package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.util.numbers.BigFraction
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextUp

// McCormick envelope over the integer operand's live bounds and the real operand's declared ones. Every row
// bakes an integer-operand endpoint, so both sides must be stated by the model: an invented live box would
// cut off products the model admits.
//
// McCormick, "Computability of global solutions to factorable nonconvex programs: Part I — Convex
// underestimating problems", Mathematical Programming 10 (1976), https://doi.org/10.1007/BF01580665.
internal fun RealProduct.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.statesBothBounds(intOperand)) return
    val resCol = builder.realColumn(result)
    val opCol = builder.realColumn(realOperand)
    if (resCol < 0 || opCol < 0) return // builder has no real-column backing (e.g. a presolve fake)
    val dom = builder.liveDomain(intOperand)
    val lo = dom.min
    val hi = dom.max
    val operandMagnitude = max(abs(lo.toDouble()), abs(hi.toDouble())).nextUp()
    val productMagnitude = (builder.realMagnitude(result) +
        (operandMagnitude * builder.realMagnitude(realOperand)).nextUp()).nextUp()
    if (lo == hi) {
        builder.derivedRealRow(intArrayOf(resCol, opCol), doubleArrayOf(1.0, -lo.toDouble()), LinearOp.EQ, 0.0,
            LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(productMagnitude)))
        return
    }
    if (!realOperandLo.isFinite() || !realOperandHi.isFinite()) return

    // `w = x·y` with `x = intOperand`, `y = realOperand`, `w = result`.
    val nCol = builder.intColumn(intOperand)
    val cols = intArrayOf(resCol, opCol, nCol)
    val xL = lo.toDouble()
    val xH = hi.toDouble()
    val yL = realOperandLo
    val yH = realOperandHi
    // A chord differs from the product by (x - a) * (y - b), pricing a relaxed y endpoint by at most hi - lo.
    val width = (BigFraction.ofLong(hi) - BigFraction.ofLong(lo)).toDouble().nextUp()
    val lowerSlack = LpRowSlack(doubleArrayOf(1.0, width), doubleArrayOf(productMagnitude, abs(yL)))
    val upperSlack = LpRowSlack(doubleArrayOf(1.0, width), doubleArrayOf(productMagnitude, abs(yH)))
    // w ≥ xL·y + yL·x − xL·yL  and  w ≥ xH·y + yH·x − xH·yH
    builder.derivedRealRow(cols, doubleArrayOf(1.0, -xL, -yL), LinearOp.GE, -xL * yL, lowerSlack)
    builder.derivedRealRow(cols, doubleArrayOf(1.0, -xH, -yH), LinearOp.GE, -xH * yH, upperSlack)
    // w ≤ xH·y + yL·x − xH·yL  and  w ≤ xL·y + yH·x − xL·yH
    builder.derivedRealRow(cols, doubleArrayOf(1.0, -xH, -yL), LinearOp.LE, -xH * yL, lowerSlack)
    builder.derivedRealRow(cols, doubleArrayOf(1.0, -xL, -yH), LinearOp.LE, -xL * yH, upperSlack)
}
