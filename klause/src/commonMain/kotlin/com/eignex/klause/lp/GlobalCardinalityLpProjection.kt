package com.eignex.klause.lp

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.util.collections.IntArrayList

/**
 * One-hot selector model for the count-variable form `counts(k) = #{i : xs(i) = cover(k)}`: a one-hot
 * selector `z_iv ∈ [0,1]` per variable/value over `xs(i)`'s root box with `Σ_v z_iv = 1` and the
 * channel `Σ_v v·z_iv = xs(i)`, and per cover value the exact count linkage `Σ_i z_{i,cover(k)} =
 * counts(k)` — so a count variable in the objective reads a true LP bound. Large encodings and the
 * constant-bound and optional-presence forms are skipped. Rows contribute as [Contribution.HULL].
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
    for ((position, x) in xs.withIndex()) {
        if (!builder.emitOneHotValues(
                x,
                definition = { value -> LpAuxiliaryColumn(listOf(position.toLong(), value), 1L, true) },
                selector = { value, column ->
                    val ci = coverIndexByValue.getOrDefault(value, -1)
                    if (ci >= 0) selByCover[ci].add(column)
                },
            )
        ) return
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
    var result: LpSizeEstimate? = null
    sequence<Unit> { result = prepareLpHull(this@estimateLpHull, boxes) }.forEach { }
    return result
}

internal suspend fun SequenceScope<Unit>.prepareLpHull(factor: GlobalCardinality, boxes: RootBoxes): LpSizeEstimate? {
    val countVars = factor.countVars
    val presents = factor.presents
    val xs = factor.xs
    val cover = factor.cover

    if (countVars == null || presents.isNotEmpty()) return null
    if (!prepareStatesBothBounds(boxes, xs)) return null
    var cells = 0L
    for ((position, x) in xs.withIndex()) {
        cells += boxes.domain(x).values.size.toLong()
        if ((position + 1) % 256 == 0) yield(Unit)
    }
    if (cells == 0L || cells > MAX_GCC_CELLS) return null
    // One z selector per var×root-box value; (Σz=1, channel) per var + one count row per cover value.
    return LpSizeEstimate(cols = cells, rows = 2L * xs.size + cover.size)
}

/** GCCs whose total domain-cell count exceeds this are skipped — the columns would dominate. */
private const val MAX_GCC_CELLS: Int = 1024
