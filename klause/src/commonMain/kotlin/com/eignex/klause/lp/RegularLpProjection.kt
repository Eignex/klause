package com.eignex.klause.lp

import com.eignex.klause.factor.table.Regular
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

/**
 * Layer-expanded DFA flow hull — the exact convex hull of the automaton's accepting strings. An arc
 * variable `y ∈ [0,1]` per reachable `(position t, state q, symbol s)` whose transition `δ(q, s)` is
 * live. Rows: a source row `Σ y out of (0, q0) = 1`, flow conservation at every interior `(t, q)`, an
 * acceptance row at the last layer, and a channel `Σ_s s·y = seq[t]` per position. The flow polytope is
 * integral, so the LP is the true convex hull. Large arc sets and inputs with no accepting path are skipped.
 *
 * The layer expansion screens each position's symbols through its root box and the flow rows then confine
 * the column to the arcs built, so over a side the model leaves open an invented endpoint would refute
 * strings the automaton accepts — declined there.
 */
@Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
internal fun Regular.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (!builder.statesBothBounds(seq)) return
    val reach = forwardReach(builder::rootDomain)?.states ?: return
    val len = seq.size
    val s = alphabetSize
    val trans = transitions
    fun delta(state: Int, sym: Long): Int = trans[(state - 1) * s + (sym - 1).toInt()].toInt() // 1-based; 0 = dead
    val ns = numStates

    val outCols = Array(len) { arrayOfNulls<IntArrayList>(ns + 1) }
    val inCols = Array(len + 1) { arrayOfNulls<IntArrayList>(ns + 1) }
    val chanCols = Array(len) { IntArrayList() }
    val chanSym = Array(len) { IntArrayList() }
    val acceptCols = IntArrayList()
    for (t in 0 until len) {
        val box = builder.rootDomain(seq[t])
        val live = builder.liveDomain(seq[t])
        reach[t].forEach { state ->
            box.values.forEach { sym ->
                if (sym !in 1..s) return@forEach
                val nxt = delta(state, sym)
                if (nxt == 0) return@forEach
                // The arc is present while symbol sym stays in seq[t]'s live domain.
                val col = builder.auxColumn(
                    0L,
                    if (live.contains(sym)) 1L else 0L,
                    presence = longArrayOf(seq[t].toLong(), sym),
                    definition = LpAuxiliaryColumn(listOf(t.toLong(), state.toLong(), sym), 1L, true),
                )
                (outCols[t][state] ?: IntArrayList().also { outCols[t][state] = it }).add(col)
                (inCols[t + 1][nxt] ?: IntArrayList().also { inCols[t + 1][nxt] = it }).add(col)
                chanCols[t].add(col)
                chanSym[t].add(sym.toInt())
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

internal fun Regular.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    var result: LpSizeEstimate? = null
    sequence<Unit> { result = prepareLpHull(this@estimateLpHull, boxes) }.forEach { }
    return result
}

internal suspend fun SequenceScope<Unit>.prepareLpHull(factor: Regular, boxes: RootBoxes): LpSizeEstimate? {
    val seq = factor.seq

    if (!prepareStatesBothBounds(boxes, seq)) return null
    val reach = prepareForwardReach(factor, boxes::domain) ?: return null
    // arc columns + conservation (≤ arcs) + channel (len) + source + acceptance.
    return LpSizeEstimate(cols = reach.arcCount, rows = reach.arcCount + seq.size + 2L)
}

private class RegularReach(val states: Array<IntHashSet>, val arcCount: Long)

/** Forward-reachable states per layer over [domainOf]'s domains plus the total candidate-arc count. */
private fun Regular.forwardReach(domainOf: (Int) -> IntDomain): RegularReach? {
    var result: RegularReach? = null
    sequence<Unit> { result = prepareForwardReach(this@forwardReach, domainOf) }.forEach { }
    return result
}

private suspend fun SequenceScope<Unit>.prepareForwardReach(
    factor: Regular,
    domainOf: (Int) -> IntDomain,
): RegularReach? {
    val len = factor.seq.size
    val s = factor.alphabetSize
    val trans = factor.transitions
    val pending = arrayOfNulls<IntHashSet>(len + 1)
    yield(Unit)
    for (i in pending.indices) {
        pending[i] = IntHashSet()
        if ((i + 1) % 256 == 0) yield(Unit)
    }
    @Suppress("UNCHECKED_CAST")
    val reach = pending as Array<IntHashSet>
    var visits = 0
    reach[0].add(factor.q0)
    var arcCount = 0L
    for (t in 0 until len) {
        if (++visits % 256 == 0) yield(Unit)
        val dom = domainOf(factor.seq[t])
        val current = reach[t]
        for (slot in current.usedInternal.indices) {
            if (++visits % 256 == 0) yield(Unit)
            if (!current.usedInternal[slot]) continue
            val state = current.keysInternal[slot]
            // Walk the alphabet (1..s), not the domain: only symbols in range have a transition, and
            // s is the automaton's alphabet size (bounded), never the variable's span — so a wide
            // sequence domain is tested by membership, never enumerated.
            for (sym in 1..s) {
                if (++visits % 256 == 0) yield(Unit)
                if (sym.toLong() in dom) {
                    val nxt = trans[(state - 1) * s + (sym - 1)].toInt()
                    if (nxt != 0) {
                        reach[t + 1].add(nxt)
                        arcCount++
                        if (arcCount > MAX_REGULAR_ARCS) return null
                    }
                }
            }
        }
        if (reach[t + 1].isEmpty()) return null // no accepting path under these domains
    }
    if (arcCount == 0L || arcCount > MAX_REGULAR_ARCS) return null
    return RegularReach(reach, arcCount)
}

/** Above this many reachable arcs the hull is skipped — the arc columns would dominate. */
private const val MAX_REGULAR_ARCS: Int = 4096
