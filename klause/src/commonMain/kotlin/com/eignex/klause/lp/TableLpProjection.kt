package com.eignex.klause.lp

import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.IntArrayList

/**
 * Convex-hull LP relaxation: a selector column `y_t ∈ [0,1]` per allowed tuple with `Σ_t y_t = 1` and a
 * per-column channel `xs[j] = Σ_t tuple_t[j]·y_t` — the projection onto `xs` is exactly the convex hull
 * of the allowed tuples. A tuple's column exists when every entry is in the root box of its
 * variable and is pinned to 0 when any entry left the live domain. Large tables are skipped. HULL.
 *
 * A root box screens the tuples, so over a side the model leaves open an invented endpoint would drop
 * tuples the model allows and the one-hot rows would then refute them — declined there.
 */
internal fun Table.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    // An interval/wildcard cell doesn't pin its variable for that tuple, so the per-tuple channel
    // would be ill-defined; short tables skip the hull relaxation (propagation still enforces it).
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
        // The selector is present while every entry stays in its column's live domain — the
        // membership conjunction that lets the persistent relaxation re-bind this column.
        val presence = LongArray(arity * 2)
        for (col in 0 until arity) {
            presence[col * 2] = xs[col].toLong()
            presence[col * 2 + 1] = tuples[t * arity + col]
        }
        selCols.add(builder.auxColumn(0L, if (liveFeasible) 1L else 0L, presence = presence))
        rows.add(t)
    }
    val k = selCols.size
    if (k == 0) return // no tuple feasible under the root boxes — leave it to propagation
    builder.row(selCols.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
    // xs[col] − Σ_t tuple_t[col]·y_t = 0 for each column.
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
    if (hi != null) return null
    if (numTuples > MAX_TUPLES) return null
    if (!boxes.statesBothBounds(xs)) return null
    // One selector per tuple (upper bound on the root-feasible ones) + Σ y = 1 + one channel
    // per column.
    return LpSizeEstimate(cols = numTuples.toLong(), rows = 1L + arity)
}

/** Tables with more than this many tuples are skipped — the selector columns would dominate. */
private const val MAX_TUPLES: Int = 1024
