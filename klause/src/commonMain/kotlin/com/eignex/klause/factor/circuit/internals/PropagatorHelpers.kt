package com.eignex.klause.factor.circuit.internals

import com.eignex.klause.ir.values
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.util.collections.IntArrayList

internal fun buildSuccWatches(succ: IntArray): IntArray {
    val distinct = succ.toHashSet()
    val out = IntArray(distinct.size * IntEvent.COUNT)
    var w = 0
    for (v in distinct) {
        out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
        out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        out[w++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
        out[w++] = IntEvent.pack(v, IntEvent.FIXED)
    }
    return out
}

// Strong-connectivity DFS shared by the circuit and subcircuit propagators, forward over live successor
// domains or reverse over [rev]. A forward arc is followed only when [arcAllowed] holds, and [rev] must be
// pre-filtered the same way: a subcircuit self-loop is an opt-out, not a tour edge.
internal inline fun PropagationState.circuitReachesAll(
    succ: IntArray,
    n: Int,
    root: Int,
    forward: Boolean,
    rev: Array<IntArrayList>,
    target: Int,
    crossinline arcAllowed: (from: Int, to: Int) -> Boolean,
    crossinline counts: (node: Int) -> Boolean,
): Boolean {
    val seen = BooleanArray(n)
    val stack = IntArrayList()
    seen[root] = true
    var reached = if (counts(root)) 1 else 0
    stack.add(root)
    while (!stack.isEmpty()) {
        val u = stack[stack.size - 1]
        stack.removeAtSwap(stack.size - 1)
        if (forward) {
            intDomains[succ[u]].values.forEach { vLong ->
                val v = vLong.toInt()
                if (arcAllowed(u, v) && !seen[v]) {
                    seen[v] = true
                    if (counts(v)) reached++
                    stack.add(v)
                }
            }
        } else {
            val preds = rev[u]
            for (idx in 0 until preds.size) {
                val v = preds[idx]
                if (!seen[v]) {
                    seen[v] = true
                    if (counts(v)) reached++
                    stack.add(v)
                }
            }
        }
    }
    return reached == target
}

internal open class CpGate {
    var started: Boolean = false
}

// True when the gate already fired and no subscribed variable changed since, so the caller is at a fixpoint.
internal fun PropagationState.cpGateShouldSkip(factorId: Int): Boolean {
    val gate = (refPayload[factorId] as? CpGate) ?: run {
        val fresh = CpGate()
        refPayload[factorId] = fresh
        fresh
    }
    val dirty = drainIntEventDirtyVars(factorId)
    if (gate.started && dirty.isEmpty()) return true
    gate.started = true
    return false
}

internal class PredChain(val head: Int, val length: Int, val cycleDetected: Boolean)

// `pred(j) = i` iff `succ(i)` is fixed to `j`, -1 for none. A walk that loops back to its head or exceeds [n]
// steps reports a cycle.
internal fun walkPredChain(pred: IntArray, from: Int, n: Int): PredChain {
    var head = from
    var len = 1
    var cur = from
    while (true) {
        val prev = pred[cur]
        if (prev == -1) return PredChain(head, len, false)
        if (prev == head) return PredChain(head, len, true)
        head = prev
        len++
        cur = prev
        if (len > n) return PredChain(head, len, true)
    }
}

// Each propagator owns the exclusion explanation and any resulting failure record.
internal inline fun excludeClaimedSuccessors(
    state: PropagationState,
    succ: IntArray,
    claimed: IntArray,
    exclude: (variable: Int, target: Long, owner: Int, competitor: Int) -> Boolean,
): Boolean {
    val targets = IntArrayList()
    for (target in claimed.indices) if (claimed[target] >= 0) targets.add(target)
    if (targets.isEmpty()) return true
    for (i in succ.indices) {
        val v = succ[i]
        if (state.intDomains[v].min == state.intDomains[v].max) continue
        for (k in 0 until targets.size) {
            val target = targets[k]
            val owner = claimed[target]
            if (owner == i || target.toLong() !in state.intDomains[v]) continue
            if (!exclude(v, target.toLong(), owner, i)) return false
        }
    }
    return true
}
