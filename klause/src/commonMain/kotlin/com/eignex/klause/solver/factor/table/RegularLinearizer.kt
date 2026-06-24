package com.eignex.klause.solver.factor.table

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

/**
 * Layer-expanded DFA flow hull of one [Regular] — the exact convex hull of the automaton's accepting
 * strings. An arc variable `y ∈ [0,1]` per reachable `(position t, state q, symbol s)` whose transition
 * `δ(q, s)` is live (pinned to 0 when symbol `s` left the live domain of `seq[t]`). Rows: a source row
 * `Σ y out of (0, q0) = 1`, flow conservation at every interior `(t, q)`, an acceptance row at the last
 * layer, and a channel `Σ_s s·y = seq[t]` per position. The flow polytope is integral, so the LP is the
 * true convex hull. Forward reachability over the declared domains bounds the arc count
 * ([MAX_REGULAR_ARCS]); above the cap, or when no accepting path survives, it is skipped. HULL.
 */
internal class RegularLinearizer(
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: IntArray,
    private val q0: Int,
    private val accepting: IntArray,
) : Linearizer {
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        if (!builder.hullEnabled()) return
        val len = seq.size
        val s = alphabetSize
        val trans = transitions
        fun delta(state: Int, sym: Int): Int = trans[(state - 1) * s + (sym - 1)] // 1-based; 0 = dead
        val ns = numStates

        // Forward-reachable states per layer over the declared domains; bail if a layer empties.
        val reach = Array(len + 1) { IntHashSet() }
        reach[0].add(q0)
        var arcCount = 0L
        for (t in 0 until len) {
            val dom = builder.declaredDomain(seq[t])
            reach[t].forEach { state ->
                dom.forEach { sym ->
                    if (sym in 1..s) {
                        val nxt = delta(state, sym)
                        if (nxt != 0) {
                            reach[t + 1].add(nxt)
                            arcCount++
                        }
                    }
                }
            }
            if (reach[t + 1].isEmpty()) return // no accepting path under declared domains — leave to propagation
        }
        if (arcCount == 0L || arcCount > MAX_REGULAR_ARCS) return

        val outCols = Array(len) { arrayOfNulls<IntArrayList>(ns + 1) }
        val inCols = Array(len + 1) { arrayOfNulls<IntArrayList>(ns + 1) }
        val chanCols = Array(len) { IntArrayList() }
        val chanSym = Array(len) { IntArrayList() }
        val acceptCols = IntArrayList()
        for (t in 0 until len) {
            val declared = builder.declaredDomain(seq[t])
            val live = builder.liveDomain(seq[t])
            reach[t].forEach { state ->
                declared.forEach { sym ->
                    if (sym !in 1..s) return@forEach
                    val nxt = delta(state, sym)
                    if (nxt == 0) return@forEach
                    // The arc is present while symbol sym stays in seq[t]'s live domain.
                    val col = builder.auxColumn(
                        0L,
                        if (live.contains(sym)) 1L else 0L,
                        presence = intArrayOf(seq[t], sym),
                    )
                    (outCols[t][state] ?: IntArrayList().also { outCols[t][state] = it }).add(col)
                    (inCols[t + 1][nxt] ?: IntArrayList().also { inCols[t + 1][nxt] = it }).add(col)
                    chanCols[t].add(col)
                    chanSym[t].add(sym)
                    if (t == len - 1 && nxt in accepting) acceptCols.add(col)
                }
            }
        }
        // Source: one unit leaves (0, q0).
        val src = outCols[0][q0] ?: return
        if (src.isEmpty()) return
        builder.row(src.toIntArray(), LongArray(src.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        // Flow conservation at every interior node: Σ out − Σ in = 0.
        for (t in 1 until len) {
            reach[t].forEach { state ->
                val cols = IntArrayList()
                val vals = LongArrayList()
                outCols[t][state]?.let {
                    for (k in 0 until it.size) {
                        cols.add(it[k])
                        vals.add(1L)
                    }
                }
                inCols[t][state]?.let {
                    for (k in 0 until it.size) {
                        cols.add(it[k])
                        vals.add(-1L)
                    }
                }
                if (!cols.isEmpty()) {
                    builder.row(cols.toIntArray(), vals.toLongArray(), LinearOp.EQ, 0L, Contribution.HULL)
                }
            }
        }
        // Acceptance: one unit enters an accepting state at the last layer.
        if (acceptCols.isEmpty()) return // no accepting transition reachable — leave to propagation
        builder.row(acceptCols.toIntArray(), LongArray(acceptCols.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        // Channel: Σ_s s·y = seq[t] at each position.
        for (t in 0 until len) {
            val k = chanCols[t].size
            if (k == 0) return
            val cols = IntArray(k + 1)
            val vals = LongArray(k + 1)
            for (i in 0 until k) {
                cols[i] = chanCols[t][i]
                vals[i] = chanSym[t][i].toLong()
            }
            cols[k] = builder.intColumn(seq[t])
            vals[k] = -1L
            builder.row(cols, vals, LinearOp.EQ, 0L, Contribution.HULL)
        }
    }

    companion object {
        /** Above this many reachable arcs the hull is skipped — the arc columns would dominate. */
        const val MAX_REGULAR_ARCS: Int = 4096
    }
}
