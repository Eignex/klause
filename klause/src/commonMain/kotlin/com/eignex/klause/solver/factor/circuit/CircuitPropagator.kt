package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.circuit.internals.shaveClaimedFromEndpoints
import com.eignex.klause.solver.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/** CP contract for [Circuit]: propagation of the Hamiltonian-cycle constraint over successor vars. */
interface CircuitPropagator : Propagator {

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
        val ant = state.composeIntVarAtomAntecedents(succ)
        if (!tightenSuccToRange(state, succ, n)) return false
        if (n == 1) {
            val v = succ[0]
            val d = state.intDomains[v]
            if (0 !in d) return false
            if (d.min != 0 && !state.tightenIntMin(v, 0)) return false
            if (d.max != 0 && !state.tightenIntMax(v, 0)) return false
            return true
        }
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == i && d.min < d.max) {
                if (!state.tightenIntMin(v, d.min + 1, ant)) return false
            } else if (d.max == i && d.min < d.max) {
                if (!state.tightenIntMax(v, d.max - 1, ant)) return false
            } else if (d.min == d.max && d.min == i) {
                return false
            }
        }
        val pred = IntArray(n) { -1 }
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) {
                val target = d.min
                if (pred[target] != -1) return false
                pred[target] = i
            }
        }
        if (!shaveClaimedFromEndpoints(state, succ, pred, ant)) return false
        val visited = BooleanArray(n)
        val posOnPath = IntArray(n) { -1 }
        val path = IntArrayList()
        for (start in 0 until n) {
            if (visited[start]) continue
            path.clear()
            var cur = start
            while (cur in 0 until n && !visited[cur] && posOnPath[cur] < 0) {
                posOnPath[cur] = path.size
                path.add(cur)
                val sV = succ[cur]
                val sD = state.intDomains[sV]
                if (sD.min != sD.max) {
                    cur = -2
                    break
                }
                cur = sD.min
            }
            if (cur in 0 until n && posOnPath[cur] >= 0) {
                val cycleLen = path.size - posOnPath[cur]
                if (cycleLen < n) return false
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
                if (prev == -1) break
                if (prev == start) return false
                start = prev
                chainNodes++
                cur = prev
                if (chainNodes > n) return false
            }
            if (chainNodes == n) {
                if (start !in d) return false
                if (!state.tightenIntMin(v, start, ant)) return false
                if (!state.tightenIntMax(v, start, ant)) return false
            } else {
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
