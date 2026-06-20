package com.eignex.klause.solver.factor.circuit.internals

import com.eignex.klause.solver.propagation.PropagationState

internal class CycleScan(val numCycles: Int, val nodesInCycles: Int)

/** Tighten each `succ[i]` domain to `[0, n)`. Returns false on contradiction. */
internal fun tightenSuccToRange(state: PropagationState, succ: IntArray, n: Int): Boolean {
    for (i in succ.indices) {
        val v = succ[i]
        val d = state.intDomains[v]
        val newLo = maxOf(d.min, 0)
        val newHi = minOf(d.max, n - 1)
        if (newLo > newHi) return false
        if (newLo != d.min && !state.tightenIntMin(v, newLo)) return false
        if (newHi != d.max && !state.tightenIntMax(v, newHi)) return false
    }
    return true
}

/** Shave every claimed value off the other vars' domain endpoints. Returns false on contradiction.
 *  `claimed[target] == -1` means unclaimed; any other value means claimed by that position. */
internal fun shaveClaimedFromEndpoints(
    state: PropagationState,
    succ: IntArray,
    claimed: IntArray,
    ant: IntArray?,
): Boolean {
    for (i in succ.indices) {
        val v = succ[i]
        val d = state.intDomains[v]
        if (d.min == d.max) continue
        var newMin = d.min
        while (newMin < d.max && claimed[newMin] != -1 && claimed[newMin] != i) newMin++
        var newMax = d.max
        while (newMax > newMin && claimed[newMax] != -1 && claimed[newMax] != i) newMax--
        if (newMin > newMax) return false
        if (newMin != d.min && !state.tightenIntMin(v, newMin, ant)) return false
        if (newMax != d.max && !state.tightenIntMax(v, newMax, ant)) return false
    }
    return true
}

/** Functional-graph cycle decomposition: `next(i)` is each node's single out-edge, -1 a sink.
 *  Counts closed cycles and the nodes on them. */
internal fun cycleScan(next: IntArray, n: Int): CycleScan {
    val unvisited = 0
    val onStack = 1
    val done = 2
    val markers = IntArray(n)
    val enterStep = IntArray(n)
    var globalStep = 0
    var numCycles = 0
    var nodesInCycles = 0
    for (start in 0 until n) {
        if (markers[start] != unvisited) continue
        var cur = start
        while (cur >= 0 && markers[cur] == unvisited) {
            markers[cur] = onStack
            enterStep[cur] = globalStep++
            cur = next[cur]
        }
        if (cur >= 0 && markers[cur] == onStack) {
            numCycles++
            nodesInCycles += globalStep - enterStep[cur]
        }
        for (i in 0 until n) if (markers[i] == onStack) markers[i] = done
    }
    return CycleScan(numCycles, nodesInCycles)
}
