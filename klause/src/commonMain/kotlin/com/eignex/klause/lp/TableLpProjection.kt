package com.eignex.klause.lp

import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearOp
import com.eignex.util.collections.IntArrayList

// One selector per allowed tuple, `Σ y(t) = 1`, and a channel per column: the projection onto `xs` is the
// exact convex hull of the tuples. Root boxes screen the tuples, so an open-sided column is declined: an
// invented endpoint would drop tuples the model allows.
internal fun Table.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    // An interval or wildcard cell does not pin its variable, so the per-tuple channel is ill-defined.
    if (hi != null) return
    if (numTuples > MAX_TUPLES) return
    if (!builder.statesBothBounds(xs)) return
    val box = Array(arity) { c -> builder.rootDomain(xs[c]) }
    val live = Array(arity) { c -> builder.liveDomain(xs[c]) }
    val selCols = IntArrayList()
    val rows = IntArrayList()
    for (t in 0 until numTuples) {
        var rootFeasible = true
        var liveFeasible = true
        for (col in 0 until arity) {
            val v = tuples[t * arity + col]
            if (v !in box[col]) {
                rootFeasible = false
                break
            }
            if (v !in live[col]) liveFeasible = false
        }
        if (!rootFeasible) continue
        // The membership conjunction lets the persistent relaxation re-bind this column.
        val presence = LongArray(arity * 2)
        for (col in 0 until arity) {
            presence[col * 2] = xs[col].toLong()
            presence[col * 2 + 1] = tuples[t * arity + col]
        }
        selCols.add(
            builder.auxColumn(
                0L,
                if (liveFeasible) 1L else 0L,
                presence = presence,
                definition = LpAuxiliaryColumn(listOf(t.toLong()), 1L, true),
            ),
        )
        rows.add(t)
    }
    val k = selCols.size
    if (k == 0) return
    builder.row(selCols.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
    for (col in 0 until arity) {
        val cols = IntArray(k + 1)
        val vals = LongArray(k + 1)
        for (s in 0 until k) {
            cols[s] = selCols[s]
            vals[s] = -tuples[rows[s] * arity + col]
        }
        cols[k] = builder.intColumn(xs[col])
        vals[k] = 1L
        builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
    }
}

internal fun Table.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    var result: LpSizeEstimate? = null
    sequence<Unit> { result = prepareLpHull(this@estimateLpHull, boxes) }.forEach { }
    return result
}

internal suspend fun SequenceScope<Unit>.prepareLpHull(factor: Table, boxes: RootBoxes): LpSizeEstimate? {
    val hi = factor.hi
    val numTuples = factor.numTuples
    val xs = factor.xs
    val arity = factor.arity

    if (hi != null) return null
    if (numTuples > MAX_TUPLES) return null
    if (!prepareStatesBothBounds(boxes, xs)) return null
    // Counts every tuple's selector, an upper bound on the root-feasible ones.
    return LpSizeEstimate(cols = numTuples.toLong(), rows = 1L + arity)
}

// Larger tables are skipped: their selector columns would dominate the LP.
private const val MAX_TUPLES: Int = 1024
