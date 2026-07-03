package com.eignex.klause.factor.circuit.internals

import com.eignex.klause.propagation.IntEvent

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
