package com.eignex.klause.factor.circuit.internals

import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.ir.values
import com.eignex.klause.util.IntArrayList

/** Subscribe to all four event types for each distinct variable in [succ]. */
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

/**
 * Directed reachability from [root] over the candidate-arc graph of a circuit successor array, the
 * strong-connectivity DFS both [com.eignex.klause.factor.circuit.CircuitPropagator] and
 * [com.eignex.klause.factor.circuit.SubcircuitPropagator] run (once forward via each node's live
 * successor domain, once reverse via the caller-built [rev] adjacency). A *forward* arc `(u, v)` is
 * followed only when [arcAllowed] holds (circuit: any `v` in range; subcircuit: also `v != u`, since
 * a self-loop is an opt-out, not a tour edge) — [rev] is expected pre-filtered the same way. A
 * reached node counts toward the total when [counts] (circuit: every node; subcircuit: only
 * mandatory nodes). Returns true iff [target] nodes are counted as reached.
 */
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
        stack.removeAt(stack.size - 1)
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

/** Gate used to skip propagation until the first real domain event. */
internal class CpGate {
    var started: Boolean = false
}

/**
 * Open (or lazily install) the [CpGate] stored in `refPayload[factorId]` and drain the factor's
 * int-event delta. Returns `true` when the propagator should short-circuit — the gate has already
 * fired once and no subscribed variable changed since, so there is nothing new to deduce; the
 * caller returns `true` (fixpoint). Returns `false` on the first fire and whenever a subscribed
 * variable moved, having marked the gate started. Shared by the Circuit / Subcircuit / NValue
 * propagators, which all open with this exact ritual.
 */
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

/** Result of walking the predecessor chain from a given node. */
internal class PredChain(val head: Int, val length: Int, val cycleDetected: Boolean)

/**
 * Walks the predecessor chain from [from] using [pred] (pred(j) = i iff succ(i) is fixed to j,
 * -1 means no predecessor). Returns the chain head and length; [PredChain.cycleDetected] is true
 * when the walk loops back to the current head or exceeds [n] steps.
 */
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
