package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.factor.circuit.internals.CpGate
import com.eignex.klause.solver.factor.circuit.internals.buildSuccWatches
import com.eignex.klause.solver.factor.circuit.internals.shaveClaimedFromEndpoints
import com.eignex.klause.solver.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.solver.factor.circuit.internals.walkPredChain
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList

/** CP implementation for [Circuit]: propagation of the Hamiltonian-cycle constraint over successor vars. */
internal class CircuitPropagator(private val succ: IntArray, private val n: Int) : Propagator {

    override val initialIntEventWatches: IntArray = buildSuccWatches(succ)
    override val consumesIntEventDelta: Boolean = true

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        // Sharp reason for a premature subtour: the fixed edges forming a cycle shorter than n are
        // a complete, self-contained cause, so cite only those successor variables. Any other
        // failure (e.g. strong-connectivity) falls back to the sound whole-scope reason.
        val cycle = fixedSubtour(state)
        if (cycle != null) return collectLinearTightenAntecedents(state, cycle, excludeIdx = -1, extraLit = 0)
        return collectLinearTightenAntecedents(state, succ, excludeIdx = -1, extraLit = 0)
    }

    /** The successor variables on a fixed-edge cycle of length < n, or null if none exists. */
    private fun fixedSubtour(state: PropagationState): IntArray? {
        val nextFixed = IntArray(n) { -1 }
        for (i in 0 until n) {
            val d = state.intDomains[succ[i]]
            if (d.min == d.max && d.min in 0 until n) nextFixed[i] = d.min
        }
        val state0 = IntArray(n) // 0 unvisited, 1 on current path, 2 done
        val pos = IntArray(n) { -1 }
        val path = IntArrayList()
        for (start in 0 until n) {
            if (state0[start] != 0) continue
            path.clear()
            var cur = start
            while (cur != -1 && state0[cur] == 0) {
                state0[cur] = 1
                pos[cur] = path.size
                path.add(cur)
                cur = nextFixed[cur]
            }
            if (cur != -1 && cur in 0 until n && pos[cur] >= 0) {
                val cycleStart = pos[cur]
                val cycleLen = path.size - cycleStart
                if (cycleLen < n) {
                    return IntArray(cycleLen) { succ[path[cycleStart + it]] }
                }
            }
            for (k in 0 until path.size) {
                state0[path[k]] = 2
                pos[path[k]] = -1
            }
        }
        return null
    }

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
            val c = walkPredChain(pred, i, n)
            if (c.cycleDetected) return false
            val start = c.head
            val chainNodes = c.length
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
        if (n >= 2 && !stronglyConnected(state)) return false
        return true
    }

    /**
     * Necessary condition for a Hamiltonian circuit: the candidate-successor digraph (node `i` →
     * every value still in `succ(i)`'s domain) must be strongly connected, since the circuit itself
     * is a strongly-connected spanning subgraph. Tested as forward + reverse reachability from node
     * 0 — both must cover all `n` nodes. Done right, per-arc SCC pruning reduces to exactly this
     * check (an arc between two SCCs is in no cycle, but if any such arc exists the graph is already
     * not strongly connected). A correct circuit never trips it, so it only ever rules out dead ends.
     */
    private fun stronglyConnected(state: PropagationState): Boolean {
        val rev = Array(n) { IntArrayList() }
        for (i in 0 until n) {
            state.intDomains[succ[i]].forEach { k -> if (k in 0 until n) rev[k].add(i) }
        }
        return reachesAll(state, forward = true, rev = rev) && reachesAll(state, forward = false, rev = rev)
    }

    private fun reachesAll(state: PropagationState, forward: Boolean, rev: Array<IntArrayList>): Boolean {
        val seen = BooleanArray(n)
        val stack = IntArrayList()
        seen[0] = true
        stack.add(0)
        var count = 1
        while (!stack.isEmpty()) {
            val u = stack[stack.size - 1]
            stack.removeAt(stack.size - 1)
            if (forward) {
                state.intDomains[succ[u]].forEach { v ->
                    if (v in 0 until n && !seen[v]) {
                        seen[v] = true
                        count++
                        stack.add(v)
                    }
                }
            } else {
                val preds = rev[u]
                for (idx in 0 until preds.size) {
                    val v = preds[idx]
                    if (!seen[v]) {
                        seen[v] = true
                        count++
                        stack.add(v)
                    }
                }
            }
        }
        return count == n
    }
}
