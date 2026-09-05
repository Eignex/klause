package com.eignex.klause.lp

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * One-hot selector model for the count-variable form `counts(k) = #{i : xs(i) = cover(k)}`: a one-hot
 * selector `z_iv ∈ [0,1]` per variable/value over `xs[i]`'s root box with `Σ_v z_iv = 1` and the
 * channel `Σ_v v·z_iv = xs(i)`, and per cover value the exact count linkage `Σ_i z_{i,cover(k)} =
 * counts(k)` — so a count variable in the objective reads a true LP bound. Large encodings and the
 * constant-bound and optional-presence forms are skipped. HULL.
 *
 * The selectors enumerate each variable's root box and the channel then confines the column to what was
 * enumerated, so over a side the model leaves open the encoding would exclude values the model admits,
 * and the count rows would undercount them — declined there.
 */
internal fun GlobalCardinality.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (presents.isNotEmpty()) return // count is over present vars only — defer
    val counts = countVars ?: return // constant-bound form has no count var to bound
    if (!builder.statesBothBounds(xs)) return
    var cells = 0L
    for (x in xs) cells += builder.rootDomain(x).values.size.toLong()
    if (cells == 0L || cells > MAX_GCC_CELLS) return
    // Selector columns per cover value, indexed by cover position via [coverIndexByValue], whose
    // Long keys address cover values across the full value range.
    val selByCover = Array(cover.size) { IntArrayList() }
    for (x in xs) {
        val box = builder.rootDomain(x)
        val live = builder.liveDomain(x)
        val sel = IntArrayList()
        val selVal = LongArrayList()
        box.values.forEach { v ->
            // The selector z_xv is present while value v stays in x's live domain.
            val z = builder.auxColumn(0L, if (live.contains(v)) 1L else 0L, presence = longArrayOf(x.toLong(), v))
            sel.add(z)
            selVal.add(v)
            val ci = coverIndexByValue.getOrDefault(v, -1) // only cover values carry a count row
            if (ci >= 0) selByCover[ci].add(z)
        }
        val k = sel.size
        if (k == 0) return // a variable with no values in its root box — leave it to propagation
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
    // Σ_i z_{i,cover(k)} − counts(k) = 0 per cover value (a cover value in no domain forces 0).
    for (k in cover.indices) {
        val sel = selByCover[k]
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

internal fun GlobalCardinality.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    if (countVars == null || presents.isNotEmpty()) return null
    if (!boxes.statesBothBounds(xs)) return null
    var cells = 0L
    for (x in xs) cells += boxes.domain(x).values.size.toLong()
    if (cells == 0L || cells > MAX_GCC_CELLS) return null
    // One z selector per var×root-box value; (Σz=1, channel) per var + one count row per cover value.
    return LpSizeEstimate(cols = cells, rows = 2L * xs.size + cover.size)
}

/** GCCs whose total domain-cell count exceeds this are skipped — the columns would dominate. */
private const val MAX_GCC_CELLS: Int = 1024
