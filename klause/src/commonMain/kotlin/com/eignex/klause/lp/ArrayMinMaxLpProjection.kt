package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.ir.LinearOp

// The envelope (`result ≥ xs(i)` for max) is CORE; the tight face is HULL: one-hot selectors `z(i)` with
// `Σ z(i) = 1` and a big-M row per operand forcing `result = xs(i)` when `z(i) = 1`.
internal fun ArrayMinMax.emitLpRelaxation(builder: RelaxationBuilder) {
    val resultCol = builder.intColumn(result)
    val op = if (max) LinearOp.GE else LinearOp.LE
    for (x in xs) {
        builder.row(intArrayOf(resultCol, builder.intColumn(x)), longArrayOf(1L, -1L), op, 0L)
    }
    if (builder.hullEnabled()) emitTightFace(builder, resultCol)
}

private fun ArrayMinMax.emitTightFace(builder: RelaxationBuilder, resultCol: Int) {
    val n = xs.size
    if (n == 0) return
    // `M(i)` spans the two root boxes, so it bounds `|result − xs(i)|` only while both are the model's
    // own; over an invented endpoint the row could cut off a genuine solution.
    if (!builder.statesBothBounds(result)) return
    val rDom = builder.rootDomain(result)
    // `-1` where no row links the operand: an unstated endpoint, or an empty box (left to propagation).
    val bigM = LongArray(n) { i ->
        val x = xs[i]
        if (!builder.statesBothBounds(x)) return@LongArray -1L
        val xDom = builder.rootDomain(x)
        maxOf(rDom.max, xDom.max) - minOf(rDom.min, xDom.min)
    }
    if (bigM.none { it >= 0L }) return
    // Unlinked operands keep a selector: dropping them would force the extremum onto a linked operand.
    val sel = IntArray(n) { builder.auxColumn(0L, 1L) }
    builder.row(sel, LongArray(n) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
    for (i in 0 until n) {
        val m = bigM[i]
        if (m < 0L) continue
        val xCol = builder.intColumn(xs[i])
        val z = sel[i]
        // max: result − xs(i) + M·z(i) ≤ M.  min: xs(i) − result + M·z(i) ≤ M.
        val cols = if (max) intArrayOf(resultCol, xCol, z) else intArrayOf(xCol, resultCol, z)
        builder.row(cols, longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
    }
}
