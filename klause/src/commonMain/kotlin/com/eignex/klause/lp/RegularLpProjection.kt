package com.eignex.klause.lp

import com.eignex.klause.factor.table.Regular
import com.eignex.klause.ir.IntDomain
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.storage.forEachSlot

// Layer-expanded DFA flow hull: the flow polytope is integral, so the LP is the exact convex hull of the
// accepted strings. The flow rows confine each column to the arcs built from its root box, so an
// open-sided column is declined: an invented endpoint would refute accepted strings.
//
// Côté, Gendron and Rousseau, "Modeling the Regular Constraint with Integer Programming", CPAIOR 2007,
// https://doi.org/10.1007/978-3-540-72397-4_3.
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

    val flow = LayeredFlowRows(len) { ns + 1 }
    for (t in 0 until len) {
        val box = builder.rootDomain(seq[t])
        val live = builder.liveDomain(seq[t])
        reach[t].forEach { state ->
            for (symbol in 1..s) {
                val sym = symbol.toLong()
                if (sym !in box) continue
                val nxt = delta(state, sym)
                if (nxt == 0) continue
                val col = builder.auxColumn(
                    0L,
                    if (live.contains(sym)) 1L else 0L,
                    presence = longArrayOf(seq[t].toLong(), sym),
                    definition = LpAuxiliaryColumn(listOf(t.toLong(), state.toLong(), sym), 1L, true),
                )
                flow.arc(t, state, nxt, col, sym)
                if (t == len - 1 && nxt in accepting) flow.accepting.add(col)
            }
        }
    }
    flow.emit(builder, seq, reach, q0)
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
    return LpSizeEstimate(cols = reach.arcCount, rows = reach.arcCount + seq.size + 2L)
}

private class RegularReach(val states: Array<IntHashSet>, val arcCount: Long)

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
        current.forEachSlot { occupied, state ->
            if (++visits % 256 == 0) yield(Unit)
            if (!occupied) return@forEachSlot
            // Walk the bounded alphabet, not the domain, so a wide sequence domain is never enumerated.
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
        if (reach[t + 1].isEmpty()) return null
    }
    if (arcCount == 0L || arcCount > MAX_REGULAR_ARCS) return null
    return RegularReach(reach, arcCount)
}

// Larger arc sets are skipped: their columns would dominate the LP.
private const val MAX_REGULAR_ARCS: Int = 4096
