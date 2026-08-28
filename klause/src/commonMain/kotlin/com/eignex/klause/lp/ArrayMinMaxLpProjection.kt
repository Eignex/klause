package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.ir.LinearOp

/**
 * LP relaxation: the always-emitted envelope (`result ≥ xs[i]` for max, `result ≤ xs[i]` for min) as
 * CORE rows, plus the tight convex-hull face as HULL — one-hot selectors `z_i` with `Σ z_i = 1` and a
 * per-operand big-M row forcing `result = xs[i]` when `z_i = 1`. Each `M_i` comes from the declared
 * domains, so it bounds `|result − xs[i]|` globally and the rows hold at every integer solution.
 */
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
    val sel = IntArray(n) { builder.auxColumn(0L, 1L) } // free binaries z_i ∈ [0,1]
    builder.row(sel, LongArray(n) { 1L }, LinearOp.EQ, 1L, Contribution.HULL) // Σ z_i = 1
    val rDom = builder.declaredDomain(result)
    for (i in 0 until n) {
        val x = xs[i]
        val xDom = builder.declaredDomain(x)
        val m = maxOf(rDom.max, xDom.max) - minOf(rDom.min, xDom.min)
        if (m < 0L) continue
        val xCol = builder.intColumn(x)
        val z = sel[i]
        // max: result − xs[i] + M·z_i ≤ M.  min: xs[i] − result + M·z_i ≤ M.
        val cols = if (max) intArrayOf(resultCol, xCol, z) else intArrayOf(xCol, resultCol, z)
        builder.row(cols, longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
    }
}
