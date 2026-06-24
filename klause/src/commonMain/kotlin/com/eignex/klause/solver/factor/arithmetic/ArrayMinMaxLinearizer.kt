package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder

/**
 * LP relaxation of [ArrayMinMax] `result = max(xs)` / `min(xs)`: the always-emitted envelope
 * (`result ≥ xs[i]` for max, `result ≤ xs[i]` for min) as CORE rows, plus the Anderson tight face as
 * HULL — one-hot selectors `z_i` with `Σ z_i = 1` and a per-operand big-M row forcing `result = xs[i]`
 * when `z_i = 1`, bounding the extremum from the tight side too. Each `M_i` comes from the declared
 * domains, so it bounds `|result − xs[i]|` globally and the rows hold at every integer solution.
 */
internal class ArrayMinMaxLinearizer(
    private val result: Int,
    private val xs: IntArray,
    private val max: Boolean,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val resultCol = builder.intColumn(result)
        val op = if (max) LinearOp.GE else LinearOp.LE
        for (x in xs) {
            builder.row(intArrayOf(resultCol, builder.intColumn(x)), longArrayOf(1L, -1L), op, 0L)
        }
        if (builder.hullEnabled()) tightFace(builder, resultCol)
    }

    private fun tightFace(builder: RelaxationBuilder, resultCol: Int) {
        val n = xs.size
        if (n == 0) return
        val sel = IntArray(n) { builder.auxColumn(0L, 1L) } // free binaries z_i ∈ [0,1]
        builder.row(sel, LongArray(n) { 1L }, LinearOp.EQ, 1L, Contribution.HULL) // Σ z_i = 1
        val rDom = builder.declaredDomain(result)
        for (i in 0 until n) {
            val x = xs[i]
            val xDom = builder.declaredDomain(x)
            val m = maxOf(rDom.max, xDom.max).toLong() - minOf(rDom.min, xDom.min).toLong()
            if (m < 0L) continue
            val xCol = builder.intColumn(x)
            val z = sel[i]
            // max: result − xs[i] + M·z_i ≤ M.  min: xs[i] − result + M·z_i ≤ M.
            val cols = if (max) intArrayOf(resultCol, xCol, z) else intArrayOf(xCol, resultCol, z)
            builder.row(cols, longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
        }
    }
}
