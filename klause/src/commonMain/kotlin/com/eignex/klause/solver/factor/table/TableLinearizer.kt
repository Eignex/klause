package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.LinearizerEstimate
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.util.IntArrayList

/**
 * Convex-hull LP relaxation of one [Table] `(xs) ∈ tuples`: a selector column `y_t ∈ [0,1]` per allowed
 * tuple with `Σ_t y_t = 1` and a per-column channel `xs[j] = Σ_t tuple_t[j]·y_t` — the projection onto
 * `xs` is exactly the convex hull of the allowed tuples. A tuple's column exists when every entry is in
 * the declared domain of its variable (layout stable across nodes) and is pinned to 0 when any entry
 * left the live domain. Tables with more than [MAX_TUPLES] rows are skipped. HULL (gated by `tableHull`).
 */
internal class TableLinearizer(
    private val xs: IntArray,
    private val tuples: IntArray,
    private val arity: Int,
    private val numTuples: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        if (numTuples > MAX_TUPLES) return
        val declared = Array(arity) { c -> builder.declaredDomain(xs[c]) }
        val live = Array(arity) { c -> builder.liveDomain(xs[c]) }
        val selCols = IntArrayList()
        val rows = IntArrayList()
        for (t in 0 until numTuples) {
            var declaredFeasible = true
            var liveFeasible = true
            for (col in 0 until arity) {
                val v = tuples[t * arity + col]
                if (v !in declared[col]) {
                    declaredFeasible = false
                    break
                }
                if (v !in live[col]) liveFeasible = false
            }
            if (!declaredFeasible) continue
            // The selector is present while every entry stays in its column's live domain — the
            // membership conjunction that lets the persistent relaxation re-bind this column.
            val presence = IntArray(arity * 2)
            for (col in 0 until arity) {
                presence[col * 2] = xs[col]
                presence[col * 2 + 1] = tuples[t * arity + col]
            }
            selCols.add(builder.auxColumn(0L, if (liveFeasible) 1L else 0L, presence = presence))
            rows.add(t)
        }
        val k = selCols.size
        if (k == 0) return // no tuple feasible under the declared domains — leave it to propagation
        builder.row(selCols.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        // xs[col] − Σ_t tuple_t[col]·y_t = 0 for each column.
        for (col in 0 until arity) {
            val cols = IntArray(k + 1)
            val vals = LongArray(k + 1)
            for (s in 0 until k) {
                cols[s] = selCols[s]
                vals[s] = -tuples[rows[s] * arity + col].toLong()
            }
            cols[k] = builder.intColumn(xs[col])
            vals[k] = 1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    override fun sizeEstimate(domains: Array<IntDomain>): LinearizerEstimate? {
        if (numTuples > MAX_TUPLES) return null
        // One selector per tuple (upper bound on the declared-feasible ones) + Σ y = 1 + one channel
        // per column.
        return LinearizerEstimate(cols = numTuples.toLong(), rows = 1L + arity)
    }

    companion object {
        /** Tables with more than this many tuples are skipped — the selector columns would dominate. */
        const val MAX_TUPLES: Int = 1024
    }
}
