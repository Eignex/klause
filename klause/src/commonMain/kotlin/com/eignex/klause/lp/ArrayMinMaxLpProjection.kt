package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.ir.LinearOp

/**
 * LP relaxation: the always-emitted envelope (`result ≥ xs[i]` for max, `result ≤ xs[i]` for min) as
 * CORE rows, plus the tight convex-hull face as HULL — one-hot selectors `z_i` with `Σ z_i = 1` and a
 * per-operand big-M row forcing `result = xs[i]` when `z_i = 1`. Each `M_i` comes from the root boxes of
 * the two columns it spans, and is emitted only where the model states both — there it bounds
 * `|result − xs[i]|` globally and the row holds at every integer solution.
 *
 * The envelope leans on no endpoint and is always emitted.
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
    // `M_i` spans the two root boxes, so it bounds `|result − xs[i]|` only while both are the model's
    // own; over an invented endpoint the pair may separate further at a genuine solution and the row
    // would cut it off. The result's box gates the whole face — no operand row survives without it.
    if (!builder.statesBothBounds(result)) return
    // Σ z_i = 1 keeps a selector for every operand, linked or not: dropping the unlinked ones would
    // force the extremum onto an operand that carries a row, which the factor never says.
    val linked = BooleanArray(n) { builder.statesBothBounds(xs[it]) }
    if (linked.none { it }) return
    val sel = IntArray(n) { builder.auxColumn(0L, 1L) } // free binaries z_i ∈ [0,1]
    builder.row(sel, LongArray(n) { 1L }, LinearOp.EQ, 1L, Contribution.HULL) // Σ z_i = 1
    val rDom = builder.rootDomain(result)
    for (i in 0 until n) {
        if (!linked[i]) continue
        val x = xs[i]
        val xDom = builder.rootDomain(x)
        val m = maxOf(rDom.max, xDom.max) - minOf(rDom.min, xDom.min)
        if (m < 0L) continue
        val xCol = builder.intColumn(x)
        val z = sel[i]
        // max: result − xs[i] + M·z_i ≤ M.  min: xs[i] − result + M·z_i ≤ M.
        val cols = if (max) intArrayOf(resultCol, xCol, z) else intArrayOf(xCol, resultCol, z)
        builder.row(cols, longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
    }
}
