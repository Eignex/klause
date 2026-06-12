package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntIntMap

/** Shared scaffolding for the successor-array cycle factors [Circuit] and [Subcircuit]: LS cost
 *  plumbing plus the domain-range / pigeonhole / cycle-scan pruning helpers. */
abstract class SuccessorCycleFactor(
    /** Successor variable id per node. */
    val succ: IntArray,
) : Factor {

    protected val n: Int = succ.size

    private val positionOfVar: IntIntMap = IntIntMap.build(succ, IntArray(n) { it }, absent = -1)

    final override val boolVars: IntArray = EmptyIntArray
    final override val intVars: IntArray = succ

    protected abstract fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Int

    final override fun initialize(state: LocalSearchState, factorId: Int) {
        state.intPayload[factorId] = computeCost(state, replaceAt = -1, replaceWith = 0)
    }

    final override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = state.intPayload[factorId] > 0

    final override fun violationDegree(state: LocalSearchState, factorId: Int): Int = state.intPayload[factorId]

    final override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val pos = positionOfVar[intVar]
        if (pos < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = pos, replaceWith = newValue)
        return newCost - oldCost
    }

    final override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        if (positionOfVar[intVar] < 0) return 0
        val oldCost = state.intPayload[factorId]
        val newCost = computeCost(state, replaceAt = -1, replaceWith = 0)
        state.intPayload[factorId] = newCost
        return newCost - oldCost
    }

    protected fun tightenSuccToRange(state: PropagationState): Boolean {
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

    protected fun shaveClaimedFromEndpoints(state: PropagationState, claimed: IntArray, ant: IntArray?): Boolean {
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

    protected class CycleScan(val numCycles: Int, val nodesInCycles: Int)

    // Functional-graph cycle decomposition: next(i) is each node's single out-edge, -1 a sink
    // (out-of-range, self-loop, or excluded). Counts closed cycles and the nodes on them.
    protected fun cycleScan(next: IntArray): CycleScan {
        val unvisited = 0
        val onStack = 1
        val done = 2
        val markers = IntArray(n) // 0 = unvisited
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
                // Returned to a node on the current path → cycle from `cur` to end-of-path.
                numCycles++
                nodesInCycles += globalStep - enterStep[cur]
            }
            // Settle path nodes as done so they're never revisited.
            for (i in 0 until n) if (markers[i] == onStack) markers[i] = done
        }
        return CycleScan(numCycles, nodesInCycles)
    }
}
