package com.eignex.klause.factor.circuit.internals

import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState

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
