package com.eignex.klause.lp

import com.eignex.klause.factor.global.NValue
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.MutableLongIntMap

/**
 * One-hot value model: a per-value "used" column `y_v ∈ [0,1]`, a one-hot selector `z_iv ∈ [0,1]`
 * per variable/value with `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and `y_v ≥ z_iv`. The
 * distinct count `Σ_v y_v` relates to `n` by the mode (`Eq`: `Σ_v y_v = n`, `AtMost`: `Σ_v y_v ≤ n`,
 * `AtLeast`: `Σ_v y_v ≥ n`), so minimising `n` reads a real lower bound. Large encodings and
 * optional-presence forms are skipped.
 *
 * The selectors enumerate each variable's root box and the channel then confines the column to what was
 * enumerated, so over a side the model leaves open the encoding would exclude values the model admits —
 * declined there.
 */
internal fun NValue.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (presents.isNotEmpty()) return // count is over present vars only — defer
    if (!builder.statesBothBounds(xs)) return
    var cells = 0L
    for (x in xs) cells += builder.rootDomain(x).values.size.toLong()
    if (cells == 0L || cells > MAX_NVALUE_CELLS) return
    val exceptValues = exceptSet.toSet()
    val yCols = IntArrayList()
    val yByValue = MutableLongIntMap()
    fun yOf(v: Long): Int {
        val existing = yByValue.getOrDefault(v, -1) // columns are non-negative, so -1 marks absent
        if (existing >= 0) return existing
        // The "used" indicator is free in [0,1] regardless of the live domains — an empty
        // requirement keeps it present so the relaxation stays persistent.
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
                        builder.row(intArrayOf(column, yOf(value)), longArrayOf(1L, -1L), LinearOp.LE, 0L, Contribution.HULL)
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
    // z (per var×value) + y (≤ distinct values ≤ cells) columns; y≥z rows + (Σz=1, channel) per
    // var + the count row.
    return LpSizeEstimate(cols = 2L * cells, rows = cells + 2L * xs.size + 1L)
}

/** NValues whose total domain-cell count exceeds this are skipped — the columns would dominate. */
private const val MAX_NVALUE_CELLS: Int = 1024
