package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.circuit.internals.shaveClaimedFromEndpoints
import com.eignex.klause.solver.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/** CP contract for [Subcircuit]: propagation of the optional-cycle constraint over successor vars. */
interface SubcircuitPropagator : Propagator {

    /** Successor variable id per node. */
    val succ: IntArray

    /** Number of nodes. */
    val n: Int

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val gate = (state.refPayload[factorId] as? CpGate) ?: run {
            val fresh = CpGate()
            state.refPayload[factorId] = fresh
            fresh
        }
        val dirty = state.drainIntEventDirtyVars(factorId)
        if (gate.started && dirty.isEmpty()) return true
        gate.started = true
        if (!tightenSuccToRange(state, succ, n)) return false
        if (n == 1) return true
        val ant = state.composeIntVarAtomAntecedents(succ)
        val claimed = IntArray(n) { -1 }
        val pred = IntArray(n) { -1 }
        for (i in succ.indices) {
            val d = state.intDomains[succ[i]]
            if (d.min != d.max) continue
            val target = d.min
            if (claimed[target] != -1) return false
            claimed[target] = i
            if (target != i) pred[target] = i
        }
        if (!shaveClaimedFromEndpoints(state, succ, claimed, ant)) return false
        var includedCount = 0
        for (i in succ.indices) {
            val d = state.intDomains[succ[i]]
            if (i < d.min || i > d.max) includedCount++
        }
        val visited = BooleanArray(n)
        val posOnPath = IntArray(n) { -1 }
        val path = IntArrayList()
        for (s in 0 until n) {
            if (visited[s]) continue
            path.clear()
            var cur = s
            while (cur in 0 until n && !visited[cur] && posOnPath[cur] < 0) {
                val d = state.intDomains[succ[cur]]
                if (d.min != d.max || d.min == cur) break
                posOnPath[cur] = path.size
                path.add(cur)
                cur = d.min
            }
            if (cur in 0 until n && posOnPath[cur] >= 0) {
                val cycleLen = path.size - posOnPath[cur]
                if (includedCount > cycleLen) return false
            }
            for (k in 0 until path.size) {
                visited[path[k]] = true
                posOnPath[path[k]] = -1
            }
        }
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            var start = i
            var chainNodes = 1
            var cur = i
            while (true) {
                val prev = pred[cur]
                if (prev == -1 || prev == start) break
                start = prev
                chainNodes++
                cur = prev
                if (chainNodes > n) break
            }
            if (start != i && includedCount > chainNodes) {
                if (start == d.min && d.min < d.max) {
                    if (!state.tightenIntMin(v, d.min + 1, ant)) return false
                } else if (start == d.max && d.min < d.max) {
                    if (!state.tightenIntMax(v, d.max - 1, ant)) return false
                } else if (d.min == d.max && d.min == start) {
                    return false
                }
            }
        }
        return true
    }

    private class CpGate {
        var started: Boolean = false
    }
}
