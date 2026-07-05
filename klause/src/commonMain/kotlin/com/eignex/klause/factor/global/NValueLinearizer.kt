package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.LinearizerEstimate
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntIntMap

/**
 * One-hot value model for one [NValue] `n = |distinct(xs)|` (and the AtMost / AtLeast variants): a
 * per-value "used" column `y_v ∈ [0,1]`, a one-hot selector `z_iv ∈ [0,1]` per variable/value with
 * `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and `y_v ≥ z_iv`. The distinct count `Σ_v y_v`
 * relates to `n` by the mode (`Eq → =`, `AtMost → ≥`, `AtLeast → ≤`), so minimising `n` reads a real
 * lower bound. Gated by [MAX_NVALUE_CELLS]; optional-presence NValue is skipped. HULL (`nValueHull`).
 */
internal class NValueLinearizer(
    private val xs: IntArray,
    private val n: Int,
    private val mode: NValue.Mode,
    private val presents: IntArray,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        if (presents.isNotEmpty()) return // count is over present vars only — defer
        var cells = 0L
        for (x in xs) cells += builder.declaredDomain(x).size.toLong()
        if (cells == 0L || cells > MAX_NVALUE_CELLS) return
        val yCols = IntArrayList()
        val yByValue = MutableIntIntMap()
        fun yOf(v: Int): Int {
            val existing = yByValue.getOrDefault(v, -1) // columns are non-negative, so -1 marks absent
            if (existing >= 0) return existing
            // The "used" indicator is free in [0,1] regardless of the live domains — an empty
            // requirement keeps it present so the relaxation stays persistent (#43).
            val col = builder.auxColumn(0L, 1L, presence = EmptyIntArray)
            yCols.add(col)
            yByValue.put(v, col)
            return col
        }
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
                builder.row(intArrayOf(z, yOf(v)), longArrayOf(1L, -1L), LinearOp.LE, 0L, Contribution.HULL) // y_v ≥ z
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
        if (yCols.isEmpty()) return
        // (Σ_v y_v) − n  {EQ | LE | GE}  0, per the mode (see KDoc).
        val op = when (mode) {
            NValue.Mode.Eq -> LinearOp.EQ
            NValue.Mode.AtMost -> LinearOp.LE
            NValue.Mode.AtLeast -> LinearOp.GE
        }
        val m = yCols.size
        val cols = IntArray(m + 1)
        val vals = LongArray(m + 1)
        for (idx in 0 until m) {
            cols[idx] = yCols[idx]
            vals[idx] = 1L
        }
        cols[m] = builder.intColumn(n)
        vals[m] = -1L
        builder.row(cols, vals, op, 0L, Contribution.HULL)
    }

    override fun sizeEstimate(domains: Array<IntDomain>): LinearizerEstimate? {
        if (presents.isNotEmpty()) return null
        var cells = 0L
        for (x in xs) cells += domains[x].size.toLong()
        if (cells == 0L || cells > MAX_NVALUE_CELLS) return null
        // z (per var×value) + y (≤ distinct values ≤ cells) columns; y≥z rows + (Σz=1, channel) per
        // var + the count row.
        return LinearizerEstimate(cols = 2L * cells, rows = cells + 2L * xs.size + 1L)
    }

    companion object {
        /** NValues whose total domain-cell count exceeds this are skipped — the columns would dominate. */
        const val MAX_NVALUE_CELLS: Int = 1024
    }
}
