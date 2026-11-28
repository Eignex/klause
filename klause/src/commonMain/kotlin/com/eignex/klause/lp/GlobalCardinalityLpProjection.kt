package com.eignex.klause.lp

import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.util.collections.IntArrayList

// One-hot selectors per variable over its root box, channelled to `xs(i)`, and per cover value the exact
// count row `Σ z(i, cover(k)) = counts(k)`, so a count variable in the objective reads a true LP bound.
// The channel confines each column to its enumerated box, so an open-sided variable is declined: the
// count rows would undercount values the model admits.
internal fun GlobalCardinality.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (presents.isNotEmpty()) return
    val counts = countVars ?: return
    if (!builder.statesBothBounds(xs)) return
    var cells = 0L
    for (x in xs) cells += builder.rootDomain(x).values.size.toLong()
    if (cells == 0L || cells > MAX_GCC_CELLS) return
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
    // A cover value in no domain forces its count to 0.
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
    return LpSizeEstimate(cols = cells, rows = 2L * xs.size + cover.size)
}

// Larger encodings are skipped: their selector columns would dominate the LP.
private const val MAX_GCC_CELLS: Int = 1024
