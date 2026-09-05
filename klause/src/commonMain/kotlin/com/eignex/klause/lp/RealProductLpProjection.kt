package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.LinearOp

/**
 * McCormick envelope of `result = intOperand · realOperand` over the integer operand's live bounds and
 * the real operand's declared ones.
 *
 * Every row here bakes an endpoint of the integer operand, so both of its sides have to be the model's
 * own: over a side the model leaves open the live box is the finite lane's invention, the column enters
 * the root LP genuinely open, and the envelope would cut off products the model admits. The real
 * operand is declined the same way, by its own infinite bounds.
 */
internal fun RealProduct.emitLpRelaxation(builder: RelaxationBuilder) {
    val resCol = builder.realColumn(result)
    val opCol = builder.realColumn(realOperand)
    if (resCol < 0 || opCol < 0) return // builder has no real-column backing (e.g. a presolve fake)
    if (!builder.statesBothBounds(intOperand)) return
    val dom = builder.liveDomain(intOperand)
    val lo = dom.min
    val hi = dom.max
    if (lo == hi) {
        // result = lo·realOperand exactly (the operand is a constant in this build).
        builder.realRow(intArrayOf(resCol, opCol), doubleArrayOf(1.0, -lo.toDouble()), LinearOp.EQ, 0.0)
        return
    }
    if (!realOperandLo.isFinite() || !realOperandHi.isFinite()) return

    // McCormick envelope of `w = x·y` with `x = intOperand ∈ [lo, hi]`, `y = realOperand ∈ [yL, yH]`,
    // `w = result`. Each row is `w + cy·y + cx·x ⟨op⟩ rhs` over columns `(result, realOperand, intOperand)`.
    val nCol = builder.intColumn(intOperand)
    val cols = intArrayOf(resCol, opCol, nCol)
    val xL = lo.toDouble()
    val xH = hi.toDouble()
    val yL = realOperandLo
    val yH = realOperandHi
    // w ≥ xL·y + yL·x − xL·yL  and  w ≥ xH·y + yH·x − xH·yH
    builder.realRow(cols, doubleArrayOf(1.0, -xL, -yL), LinearOp.GE, -xL * yL)
    builder.realRow(cols, doubleArrayOf(1.0, -xH, -yH), LinearOp.GE, -xH * yH)
    // w ≤ xH·y + yL·x − xH·yL  and  w ≤ xL·y + yH·x − xL·yH
    builder.realRow(cols, doubleArrayOf(1.0, -xH, -yL), LinearOp.LE, -xH * yL)
    builder.realRow(cols, doubleArrayOf(1.0, -xL, -yH), LinearOp.LE, -xL * yH)
}
