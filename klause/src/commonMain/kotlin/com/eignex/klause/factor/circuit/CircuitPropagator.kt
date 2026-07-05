package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.circuit.internals.buildSuccWatches
import com.eignex.klause.factor.circuit.internals.circuitReachesAll
import com.eignex.klause.factor.circuit.internals.cpGateShouldSkip
import com.eignex.klause.factor.circuit.internals.shaveClaimedFromEndpoints
import com.eignex.klause.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.factor.circuit.internals.walkPredChain
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
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
        if (state.cpGateShouldSkip(factorId)) return true
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
        if (n >= 2 && !dominatorFilter(state, ant)) return false
        return true
    }

    /**
     * Dominator-based arc removal (choco `PropCircuit_ArboFiltering`). Split node 0 into a virtual
     * source whose out-arcs are node 0's candidate successors, and compute that source's dominator
     * tree over the candidate digraph. If value `y` dominates node `x` — every path from the source
     * to `x` passes through `y` — then `succ(x) = y` would close a loop `y ⇝ x → y` that bypasses
     * the source, a premature subtour; so `y` is removed from `succ(x)`. Dominators via the
     * Cooper–Harvey–Kennedy iterative algorithm (same tree as Lengauer–Tarjan, simpler to verify).
     */
    private fun dominatorFilter(state: PropagationState, ant: IntArray?): Boolean {
        val total = n + 1
        val source = n
        val succAdj = Array(total) { IntArrayList() }
        val predAdj = Array(total) { IntArrayList() }
        for (i in 0 until n) {
            val from = if (i == 0) source else i
            state.intDomains[succ[i]].forEach { y ->
                if (y in 0 until n) {
                    succAdj[from].add(y)
                    predAdj[y].add(from)
                }
            }
        }
        val idom = computeDominators(succAdj, predAdj, total, source)
        for (v in 0 until n) if (idom[v] == -1) return false // source cannot reach every node
        for (x in 1 until n) {
            val dvals = IntArrayList()
            state.intDomains[succ[x]].forEach { y -> if (y in 0 until n) dvals.add(y) }
            for (k in 0 until dvals.size) {
                val y = dvals[k]
                if (y != x && dominates(idom, source, y, x)) {
                    if (!state.excludeIntValue(succ[x], y, ant)) return false
                }
            }
        }
        return true
    }

    /** Cooper–Harvey–Kennedy iterative dominators from [source]; `idom[source]=source`, `-1` for
     *  nodes the source cannot reach. */
    private fun computeDominators(
        succAdj: Array<IntArrayList>,
        predAdj: Array<IntArrayList>,
        total: Int,
        source: Int,
    ): IntArray {
        // Postorder of the source-reachable subgraph, and each node's reverse-postorder index.
        val order = IntArrayList()
        val visited = BooleanArray(total)
        val stack = IntArrayList()
        val iter = IntArray(total)
        stack.add(source)
        visited[source] = true
        while (!stack.isEmpty()) {
            val u = stack[stack.size - 1]
            val neigh = succAdj[u]
            if (iter[u] < neigh.size) {
                val w = neigh[iter[u]]
                iter[u]++
                if (!visited[w]) {
                    visited[w] = true
                    stack.add(w)
                }
            } else {
                order.add(u)
                stack.removeAt(stack.size - 1)
            }
        }
        val rpoNum = IntArray(total) { -1 }
        val m = order.size
        for (p in 0 until m) rpoNum[order[p]] = m - 1 - p
        val idom = IntArray(total) { -1 }
        idom[source] = source
        var changed = true
        while (changed) {
            changed = false
            for (p in m - 1 downTo 0) {
                val b = order[p]
                if (b == source) continue
                var newIdom = -1
                val preds = predAdj[b]
                for (q in 0 until preds.size) {
                    val pNode = preds[q]
                    if (idom[pNode] == -1) continue
                    newIdom = if (newIdom == -1) pNode else intersect(idom, rpoNum, pNode, newIdom)
                }
                if (newIdom != -1 && idom[b] != newIdom) {
                    idom[b] = newIdom
                    changed = true
                }
            }
        }
        return idom
    }

    private fun intersect(idom: IntArray, rpoNum: IntArray, a: Int, b: Int): Int {
        // Climb toward the root, which has the smallest reverse-postorder number: advance whichever
        // finger sits deeper (larger rpoNum). The inverse comparison spins at the root forever.
        var x = a
        var y = b
        while (x != y) {
            while (rpoNum[x] > rpoNum[y]) x = idom[x]
            while (rpoNum[y] > rpoNum[x]) y = idom[y]
        }
        return x
    }

    private fun dominates(idom: IntArray, source: Int, y: Int, x: Int): Boolean {
        var c = x
        while (c != source && c != y) c = idom[c]
        return c == y
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
        // A Hamiltonian circuit visits every node, so from node 0 all n must be reachable both
        // forward (over candidate successors) and backward — any node in range is a tour edge.
        val arc = { _: Int, v: Int -> v in 0 until n }
        val counts = { _: Int -> true }
        fun reaches(forward: Boolean) = state.circuitReachesAll(
            succ,
            n,
            root = 0,
            forward = forward,
            rev = rev,
            target = n,
            arcAllowed = arc,
            counts = counts,
        )
        return reaches(forward = true) && reaches(forward = false)
    }
}
