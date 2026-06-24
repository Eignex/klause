package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.LinearizerEstimate
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.util.IntArrayList

/**
 * One-hot selector model for one count-variable [GlobalCardinality] `counts(k) = #{i : xs(i) =
 * cover(k)}`: a one-hot selector `z_iv ∈ [0,1]` per variable/value over `xs[i]`'s declared domain with
 * `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and per cover value the exact count linkage
 * `Σ_i z_{i,cover(k)} = counts(k)` — so a count variable in the objective reads a true LP bound. Gated
 * by [MAX_GCC_CELLS]; the constant-bound form and the optional-presence form are skipped (neither has a
 * count variable this hull would bound). HULL (gated by `gccCountHull`).
 */
internal class GccCountLinearizer(
    private val xs: IntArray,
    private val cover: IntArray,
    private val countVars: IntArray?,
    private val presents: IntArray,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        if (presents.isNotEmpty()) return // count is over present vars only — defer
        val counts = countVars ?: return // constant-bound form has no count var to bound
        var cells = 0L
        for (x in xs) cells += builder.declaredDomain(x).size.toLong()
        if (cells == 0L || cells > MAX_GCC_CELLS) return
        // Selector columns contributing to each cover value's count, accumulated across all xs.
        val selByCover = HashMap<Int, IntArrayList>()
        for (v in cover) selByCover[v] = IntArrayList()
        for (x in xs) {
            val declared = builder.declaredDomain(x)
            val live = builder.liveDomain(x)
            val sel = IntArrayList()
            val selVal = IntArrayList()
            declared.forEach { v ->
                // The selector z_xv is present while value v stays in x's live domain.
                val z = builder.auxColumn(0L, if (live.contains(v)) 1L else 0L, presence = intArrayOf(x, v))
                sel.add(z)
                selVal.add(v)
                selByCover[v]?.add(z) // only cover values carry a count row
            }
            val k = sel.size
            if (k == 0) return // a variable with no declared values — leave it to propagation
            builder.row(sel.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL) // Σ_v z = 1
            // Σ_v v·z − xs(i) = 0.
            val cCols = IntArray(k + 1)
            val cVals = LongArray(k + 1)
            for (s in 0 until k) {
                cCols[s] = sel[s]
                cVals[s] = selVal[s].toLong()
            }
            cCols[k] = builder.intColumn(x)
            cVals[k] = -1L
            builder.row(cCols, cVals, LinearOp.EQ, 0L, Contribution.HULL)
        }
        // Σ_i z_{i,cover(k)} − counts(k) = 0 per cover value (a cover value in no domain forces 0).
        for (k in cover.indices) {
            val sel = selByCover[cover[k]] ?: continue
            val cols = IntArray(sel.size + 1)
            val vals = LongArray(sel.size + 1)
            for (i in 0 until sel.size) {
                cols[i] = sel[i]
                vals[i] = 1L
            }
            cols[sel.size] = builder.intColumn(counts[k])
            vals[sel.size] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    override fun sizeEstimate(domains: Array<IntDomain>): LinearizerEstimate? {
        if (countVars == null || presents.isNotEmpty()) return null
        var cells = 0L
        for (x in xs) cells += domains[x].size.toLong()
        if (cells == 0L || cells > MAX_GCC_CELLS) return null
        // One z selector per var×declared-value; (Σz=1, channel) per var + one count row per cover value.
        return LinearizerEstimate(cols = cells, rows = 2L * xs.size + cover.size)
    }

    companion object {
        /** GCCs whose total domain-cell count exceeds this are skipped — the columns would dominate. */
        const val MAX_GCC_CELLS: Int = 1024
    }
}
