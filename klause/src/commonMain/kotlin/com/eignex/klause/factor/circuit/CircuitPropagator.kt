package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.circuit.internals.buildSuccWatches
import com.eignex.klause.factor.circuit.internals.circuitReachesAll
import com.eignex.klause.factor.circuit.internals.cpGateShouldSkip
import com.eignex.klause.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.factor.circuit.internals.walkPredChain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** CP implementation for [Circuit]: propagation of the Hamiltonian-cycle constraint over successor vars. */
internal class CircuitPropagator(private val succ: IntArray, private val n: Int) : Propagator {

    override val initialIntEventWatches: IntArray = buildSuccWatches(succ)
    override val consumesIntEventDelta: Boolean = true

    // The reason of the failure the last [propagate] hit, read by [conflictReason] before the engine backtracks.
    private var failure: IntArray? = null

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        failure ?: fixedSubtour(state)?.let { cycle -> Reason(state).apply { for (v in cycle) fixedVar(v) }.build() }
            ?: collectHoleAndBoundAntecedents(state, succ)

    private fun fail(reason: Reason): Boolean {
        failure = reason.build() ?: IntArray(0)
        return false
    }

    /** Clause-form literals, each false in the current state, that together justify a deduction over [succ]. */
    private inner class Reason(private val state: PropagationState) {
        private val seen = IntHashSet()
        private val literals = IntArrayList()

        fun add(lit: Int): Reason {
            if (lit != Lit.NONE && seen.add(lit)) literals.add(lit)
            return this
        }

        fun addAll(ant: IntArray?): Reason {
            ant?.forEach { add(it) }
            return this
        }

        /** The edge node [i] is fixed to. */
        fun fixed(i: Int): Reason = fixedVar(succ[i])

        fun fixedVar(v: Int): Reason = add(Lit.make(state.atomVarEq(v, state.intDomains[v].min), false))

        fun lower(v: Int): Reason {
            val d = state.intDomains[v]
            if (d.min > state.rootDomains[v].min) add(Lit.make(state.atomVarGe(v, d.min), false))
            return this
        }

        fun upper(v: Int): Reason {
            val d = state.intDomains[v]
            if (d.max < state.rootDomains[v].max) add(Lit.make(state.atomVarLe(v, d.max), false))
            return this
        }

        /** Node [u] can no longer move to [target]. */
        fun lacks(u: Int, target: Int): Reason = add(
            if (state.undoLogging) {
                state.exclusionLiteral(succ[u], target.toLong(), state.undo.size)
            } else if (target.toLong() in state.rootDomains[succ[u]]) {
                Lit.make(state.atomVarEq(succ[u], target.toLong()), true)
            } else {
                Lit.NONE
            },
        )

        /** Every node of [inside] lacks every target outside it (and outside [also]): no arc leaves the set. */
        fun closed(inside: BooleanArray, also: Int = -1): Reason {
            for (u in 0 until n) {
                if (!inside[u]) continue
                for (t in 0 until n) {
                    if (!inside[t] && t != also && t.toLong() !in state.intDomains[succ[u]]) lacks(u, t)
                }
            }
            return this
        }

        /** Every node outside [inside] lacks every target in it: no arc enters the set. */
        fun unentered(inside: BooleanArray): Reason {
            for (u in 0 until n) {
                if (inside[u]) continue
                for (t in 0 until n) if (inside[t] && t.toLong() !in state.intDomains[succ[u]]) lacks(u, t)
            }
            return this
        }

        fun build(): IntArray? = if (state.currentLevel == 0) null else literals.toIntArray()
    }

    /** The successor variables on a fixed-edge cycle of length < n, or null if none exists. */
    private fun fixedSubtour(state: PropagationState): IntArray? {
        val nextFixed = IntArray(n) { -1 }
        for (i in 0 until n) {
            val d = state.intDomains[succ[i]]
            if (d.min == d.max && d.min in 0 until n) nextFixed[i] = d.min.toInt()
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
        failure = null
        if (state.cpGateShouldSkip(factorId)) return true
        if (!tightenSuccToRange(state, succ, n)) return false
        if (n == 1) {
            val v = succ[0]
            val d = state.intDomains[v]
            if (0L !in d) return false
            if (d.min != 0L && !state.tightenIntMin(v, 0L)) return false
            if (d.max != 0L && !state.tightenIntMax(v, 0L)) return false
            return true
        }
        // No node succeeds itself in a full circuit: a fact, so a self-loop bound move cites only the bound it left.
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == i.toLong() && d.min < d.max) {
                val ant = Reason(state).lower(v).build()
                if (!state.tightenIntMin(v, d.min + 1, ant)) return fail(Reason(state).addAll(ant).upper(v))
            } else if (d.max == i.toLong() && d.min < d.max) {
                val ant = Reason(state).upper(v).build()
                if (!state.tightenIntMax(v, d.max - 1, ant)) return fail(Reason(state).addAll(ant).lower(v))
            } else if (d.min == d.max && d.min == i.toLong()) {
                return fail(Reason(state).fixed(i))
            }
        }
        val pred = IntArray(n) { -1 }
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) {
                val target = d.min.toInt()
                if (pred[target] != -1) return fail(Reason(state).fixed(i).fixed(pred[target]))
                pred[target] = i
            }
        }
        if (!shaveClaimed(state, pred)) return false
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
                cur = sD.min.toInt()
            }
            if (cur in 0 until n && posOnPath[cur] >= 0) {
                val cycleLen = path.size - posOnPath[cur]
                if (cycleLen < n) {
                    val r = Reason(state)
                    for (k in posOnPath[cur] until path.size) r.fixed(path[k])
                    return fail(r)
                }
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
            // The fixed edges of the chain ending at i: they claim every node on it but its head.
            val chain = Reason(state)
            var k = pred[i]
            var steps = 0
            while (k != -1 && steps < n) {
                chain.fixed(k)
                k = pred[k]
                steps++
            }
            if (c.cycleDetected) return fail(chain)
            val start = c.head
            val chainNodes = c.length
            val ant = chain.build()
            if (chainNodes == n) {
                // Every other node is claimed, so i closes the circuit at its head.
                if (start.toLong() !in d) return fail(Reason(state).addAll(ant).lacks(i, start))
                if (!state.tightenIntMin(v, start.toLong(), ant)) return fail(Reason(state).addAll(ant).lacks(i, start))
                if (!state.tightenIntMax(v, start.toLong(), ant)) return fail(Reason(state).addAll(ant).lacks(i, start))
            } else {
                // Moving to the chain's head would close a cycle short of n.
                if (start.toLong() == d.min && d.min < d.max) {
                    val own = Reason(state).addAll(ant).lower(v).build()
                    if (!state.tightenIntMin(v, d.min + 1, own)) return fail(Reason(state).addAll(own).upper(v))
                } else if (start.toLong() == d.max && d.min < d.max) {
                    val own = Reason(state).addAll(ant).upper(v).build()
                    if (!state.tightenIntMax(v, d.max - 1, own)) return fail(Reason(state).addAll(own).lower(v))
                } else if (d.min == d.max && d.min == start.toLong()) {
                    return fail(Reason(state).addAll(ant).fixed(i))
                }
            }
        }
        if (n >= 2 && !stronglyConnected(state)) return false
        if (n >= 2 && !dominatorFilter(state)) return false
        return true
    }

    // Shave the values other successors have claimed off each open successor's endpoints, citing those claims.
    private fun shaveClaimed(state: PropagationState, claimed: IntArray): Boolean {
        for (i in succ.indices) {
            val v = succ[i]
            val d = state.intDomains[v]
            if (d.min == d.max) continue
            var newMin = d.min
            val lowReason = Reason(state)
            while (newMin < d.max && claimed[newMin.toInt()] != -1 && claimed[newMin.toInt()] != i) {
                lowReason.fixed(claimed[newMin.toInt()])
                newMin++
            }
            var newMax = d.max
            val highReason = Reason(state)
            while (newMax > newMin && claimed[newMax.toInt()] != -1 && claimed[newMax.toInt()] != i) {
                highReason.fixed(claimed[newMax.toInt()])
                newMax--
            }
            if (newMin != d.min) {
                val ant = lowReason.lower(v).build()
                if (!state.tightenIntMin(v, newMin, ant)) return fail(Reason(state).addAll(ant).upper(v))
            }
            if (newMax != d.max) {
                val ant = highReason.upper(v).build()
                if (!state.tightenIntMax(v, newMax, ant)) return fail(Reason(state).addAll(ant).lower(v))
            }
        }
        return true
    }

    /**
     * Dominator-based arc removal. Split node 0 into a virtual
     * source whose out-arcs are node 0's candidate successors, and compute that source's dominator
     * tree over the candidate digraph. If value `y` dominates node `x` — every path from the source
     * to `x` passes through `y` — then `succ(x) = y` would close a loop `y ⇝ x → y` that bypasses
     * the source, a premature subtour; so `y` is removed from `succ(x)`. Dominators via the
     * Cooper–Harvey–Kennedy iterative algorithm (same tree as Lengauer–Tarjan, simpler to verify).
     */
    private fun dominatorFilter(state: PropagationState): Boolean {
        val total = n + 1
        val source = n
        val succAdj = Array(total) { IntArrayList() }
        val predAdj = Array(total) { IntArrayList() }
        for (i in 0 until n) {
            val from = if (i == 0) source else i
            state.intDomains[succ[i]].values.forEach { yLong ->
                if (yLong in 0 until n) {
                    val y = yLong.toInt()
                    succAdj[from].add(y)
                    predAdj[y].add(from)
                }
            }
        }
        val idom = computeDominators(succAdj, predAdj, total, source)
        // Source cannot reach every node: no arc leaves what it reaches.
        for (v in 0 until n) if (idom[v] == -1) return fail(Reason(state).closed(reachedAvoiding(succAdj, source, -1)))
        // y dominates x when, without y, the source reaches nothing past the arcs it lacks: those are the reason.
        val cut = HashMap<Int, IntArray?>()
        for (x in 1 until n) {
            val dvals = IntArrayList()
            state.intDomains[succ[x]].values.forEach { y -> if (y in 0 until n) dvals.add(y.toInt()) }
            for (k in 0 until dvals.size) {
                val y = dvals[k]
                if (y != x && dominates(idom, source, y, x)) {
                    val ant = cut.getOrPut(y) {
                        Reason(state).closed(reachedAvoiding(succAdj, source, y), also = y).build()
                    }
                    if (!state.excludeIntValue(succ[x], y.toLong(), ant)) {
                        return fail(Reason(state).addAll(ant).lower(succ[x]).upper(succ[x]))
                    }
                }
            }
        }
        return true
    }

    // The nodes the source's arcs reach without passing [avoid], node 0 standing in for the source; -1 avoids none.
    private fun reachedAvoiding(succAdj: Array<IntArrayList>, source: Int, avoid: Int): BooleanArray {
        val seen = BooleanArray(n)
        val stack = IntArrayList()
        seen[0] = true
        stack.add(source)
        while (!stack.isEmpty()) {
            val u = stack[stack.size - 1]
            stack.removeAt(stack.size - 1)
            val a = succAdj[u]
            for (j in 0 until a.size) {
                val w = a[j]
                if (w == avoid || seen[w]) continue
                seen[w] = true
                stack.add(w)
            }
        }
        return seen
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
            state.intDomains[succ[i]].values.forEach { k -> if (k in 0 until n) rev[k.toInt()].add(i) }
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
        if (!reaches(forward = true)) return fail(Reason(state).closed(reach(state, rev, forward = true)))
        if (!reaches(forward = false)) return fail(Reason(state).unentered(reach(state, rev, forward = false)))
        return true
    }

    // The nodes node 0 reaches over candidate arcs ([forward]), or that reach it.
    private fun reach(state: PropagationState, rev: Array<IntArrayList>, forward: Boolean): BooleanArray {
        val seen = BooleanArray(n)
        val stack = IntArrayList()
        seen[0] = true
        stack.add(0)
        while (!stack.isEmpty()) {
            val u = stack[stack.size - 1]
            stack.removeAt(stack.size - 1)
            if (forward) {
                state.intDomains[succ[u]].values.forEach { t ->
                    if (t in 0 until n && !seen[t.toInt()]) {
                        seen[t.toInt()] = true
                        stack.add(t.toInt())
                    }
                }
            } else {
                val p = rev[u]
                for (j in 0 until p.size) {
                    if (!seen[p[j]]) {
                        seen[p[j]] = true
                        stack.add(p[j])
                    }
                }
            }
        }
        return seen
    }
}
