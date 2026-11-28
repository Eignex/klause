package com.eignex.klause.lp

import com.eignex.klause.factor.global.NValue
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableLongIntMap

// A "used" column `y(v) ≥ z(i, v)` per value over one-hot selectors channelled to `xs(i)`; `Σ y(v)` relates
// to `n` by the mode, so minimising `n` reads a real lower bound. The channel confines each column to its
// enumerated box, so an open-sided variable is declined.
internal fun NValue.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (presents.isNotEmpty()) return
    if (!builder.statesBothBounds(xs)) return
    var cells = 0L
    for (x in xs) cells += builder.rootDomain(x).values.size.toLong()
    if (cells == 0L || cells > MAX_NVALUE_CELLS) return
    val exceptValues = exceptSet.toSet()
    val yCols = IntArrayList()
    val yByValue = MutableLongIntMap()
    fun yOf(v: Long): Int {
        val existing = yByValue.getOrDefault(v, -1)
        if (existing >= 0) return existing
        // An empty presence requirement keeps the column across live-domain changes.
        val col = builder.auxColumn(
            0L,
            1L,
            presence = emptyLongArray(),
            definition = LpAuxiliaryColumn(listOf(0L, v), 1L, true),
        )
        yCols.add(col)
        yByValue.put(v, col)
        return col
    }
    for ((position, x) in xs.withIndex()) {
        if (!builder.emitOneHotValues(
                x,
                definition = { value -> LpAuxiliaryColumn(listOf(1L, position.toLong(), value), 1L, true) },
                selector = { value, column ->
                    if (value !in exceptValues) {
                        val columns = intArrayOf(column, yOf(value))
                        builder.row(columns, longArrayOf(1L, -1L), LinearOp.LE, 0L, Contribution.HULL)
                    }
                },
            )
        ) return
    }
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

internal fun NValue.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    var result: LpSizeEstimate? = null
    sequence<Unit> { result = prepareLpHull(this@estimateLpHull, boxes) }.forEach { }
    return result
}

internal suspend fun SequenceScope<Unit>.prepareLpHull(factor: NValue, boxes: RootBoxes): LpSizeEstimate? {
    val presents = factor.presents
    val xs = factor.xs

    if (presents.isNotEmpty()) return null
    if (!prepareStatesBothBounds(boxes, xs)) return null
    var cells = 0L
    for ((position, x) in xs.withIndex()) {
        cells += boxes.domain(x).values.size.toLong()
        if ((position + 1) % 256 == 0) yield(Unit)
    }
    if (cells == 0L || cells > MAX_NVALUE_CELLS) return null
    return LpSizeEstimate(cols = 2L * cells, rows = cells + 2L * xs.size + 1L)
}

// Larger encodings are skipped: their selector columns would dominate the LP.
private const val MAX_NVALUE_CELLS: Int = 1024
