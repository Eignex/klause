package com.eignex.klause.lp

import com.eignex.klause.factor.global.NValue
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap

/**
 * One-hot value model: a per-value "used" column `y_v ∈ [0,1]`, a one-hot selector `z_iv ∈ [0,1]`
 * per variable/value with `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and `y_v ≥ z_iv`. The
 * distinct count `Σ_v y_v` relates to `n` by the mode (`Eq → =`, `AtMost → ≥`, `AtLeast → ≤`), so
 * minimising `n` reads a real lower bound. Large encodings and optional-presence forms are skipped.
 */
internal fun NValue.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (presents.isNotEmpty()) return // count is over present vars only — defer
    var cells = 0L
    for (x in xs) cells += builder.declaredDomain(x).values.size.toLong()
    if (cells == 0L || cells > MAX_NVALUE_CELLS) return
    val yCols = IntArrayList()
    val yByValue = MutableLongIntMap()
    fun yOf(v: Long): Int {
        val existing = yByValue.getOrDefault(v, -1) // columns are non-negative, so -1 marks absent
        if (existing >= 0) return existing
        // The "used" indicator is free in [0,1] regardless of the live domains — an empty
        // requirement keeps it present so the relaxation stays persistent.
        val col = builder.auxColumn(0L, 1L, presence = EmptyLongArray)
        yCols.add(col)
        yByValue.put(v, col)
        return col
    }
    for (x in xs) {
        val declared = builder.declaredDomain(x)
        val live = builder.liveDomain(x)
        val sel = IntArrayList()
        val selVal = LongArrayList()
        declared.values.forEach { v ->
            // The selector z_xv is present while value v stays in x's live domain.
            val z = builder.auxColumn(0L, if (live.contains(v)) 1L else 0L, presence = longArrayOf(x.toLong(), v))
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
            cVals[s] = selVal[s]
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

internal fun NValue.estimateLpHull(domains: Array<IntDomain>): LpSizeEstimate? {
    if (presents.isNotEmpty()) return null
    var cells = 0L
    for (x in xs) cells += domains[x].values.size.toLong()
    if (cells == 0L || cells > MAX_NVALUE_CELLS) return null
    // z (per var×value) + y (≤ distinct values ≤ cells) columns; y≥z rows + (Σz=1, channel) per
    // var + the count row.
    return LpSizeEstimate(cols = 2L * cells, rows = cells + 2L * xs.size + 1L)
}

/** NValues whose total domain-cell count exceeds this are skipped — the columns would dominate. */
private const val MAX_NVALUE_CELLS: Int = 1024
