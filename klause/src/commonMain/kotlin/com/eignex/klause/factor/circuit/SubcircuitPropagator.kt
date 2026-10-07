package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.circuit.internals.buildSuccWatches
import com.eignex.klause.factor.circuit.internals.cpGateShouldSkip
import com.eignex.klause.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.factor.circuit.internals.walkPredChain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.util.IntArrayList

/**
 * CP implementation for [Circuit]: propagation of the optional-cycle constraint over successor vars.
 *
 * Every deduction carries the edges and bounds it rests on, so the clauses conflict analysis learns from it name
 * a few successors rather than the whole tour; a clause citing every successor describes one partial tour and
 * prunes almost nothing else. A strong-connectivity failure cites the cut that separates two mandatory nodes.
 */
internal class SubcircuitPropagator(private val succ: IntArray, private val n: Int) : Propagator {

    override val initialIntEventWatches: IntArray = buildSuccWatches(succ)
    override val consumesIntEventDelta: Boolean = true

    // The reason the last failed [propagate] leaves for conflict analysis; null where it has no sharp one.
    private var failure: IntArray? = null

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = failure

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        failure = null
        if (state.cpGateShouldSkip(factorId)) return true
        if (!tightenSuccToRange(state, succ, n)) return false
        if (n == 1) return true
        val claimed = IntArray(n) { -1 }
        val pred = IntArray(n) { -1 }
        for (i in succ.indices) {
            val d = state.intDomains[succ[i]]
            if (d.min != d.max) continue
            val target = d.min.toInt()
            val other = claimed[target]
            if (other != -1) return fail(Reason(state).fixed(i).fixed(other))
            claimed[target] = i
            if (target != i) pred[target] = i
        }
        if (!shaveClaimed(state, claimed)) return false
        val mandatory = BooleanArray(n)
        var includedCount = 0
        for (i in succ.indices) {
            val d = state.intDomains[succ[i]]
            if (i < d.min || i > d.max) {
                mandatory[i] = true
                includedCount++
            }
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
                if (d.min != d.max || d.min == cur.toLong()) break
                posOnPath[cur] = path.size
                path.add(cur)
                cur = d.min.toInt()
            }
            if (cur in 0 until n && posOnPath[cur] >= 0) {
                val cycleLen = path.size - posOnPath[cur]
                if (includedCount > cycleLen) {
                    val onCycle = BooleanArray(n)
                    val reason = Reason(state)
                    for (k in posOnPath[cur] until path.size) {
                        onCycle[path[k]] = true
                        reason.fixed(path[k])
                    }
                    return fail(reason.mandatoryOutside(mandatory, onCycle))
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
            val start = c.head
            if (start == i || includedCount <= c.length) continue
            val atMin = start.toLong() == d.min
            if (!atMin && start.toLong() != d.max) continue
            // Closing the chain start → … → i now would leave a node that must be on the cycle off it.
            val onChain = BooleanArray(n)
            val reason = Reason(state)
            var node = i
            onChain[node] = true
            while (node != start) {
                node = pred[node]
                onChain[node] = true
                reason.fixed(node)
            }
            reason.mandatoryOutside(mandatory, onChain)
            if (atMin) {
                val ant = reason.lower(v).build()
                if (!state.tightenIntMin(v, d.min + 1, ant)) return fail(Reason(state).add(ant).upper(v))
            } else {
                val ant = reason.upper(v).build()
                if (!state.tightenIntMax(v, d.max - 1, ant)) return fail(Reason(state).add(ant).lower(v))
            }
        }
        if (!stronglyConnectedSubcircuit(state)) return false
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
                if (!state.tightenIntMin(v, newMin, ant)) return fail(Reason(state).add(ant).upper(v))
            }
            if (newMax != d.max) {
                val ant = highReason.upper(v).build()
                if (!state.tightenIntMax(v, newMax, ant)) return fail(Reason(state).add(ant).lower(v))
            }
        }
        return true
    }

    private fun fail(reason: Reason): Boolean {
        failure = reason.build()
        return false
    }

    /** Clause-form literals, each false in the current state, that together justify a deduction over [succ]. */
    private inner class Reason(private val state: PropagationState) {
        private val literals = IntArrayList()

        fun add(ant: IntArray?): Reason {
            ant?.forEach { if (!literals.contains(it)) literals.add(it) }
            return this
        }

        /** The edge `succ(i) = t` that node [i] is fixed to. */
        fun fixed(i: Int): Reason = lower(succ[i]).upper(succ[i])

        fun lower(v: Int): Reason {
            val d = state.intDomains[v]
            if (d.min > state.rootDomains[v].min) addLiteral(Lit.make(state.atomVarGe(v, d.min), false))
            return this
        }

        fun upper(v: Int): Reason {
            val d = state.intDomains[v]
            if (d.max < state.rootDomains[v].max) addLiteral(Lit.make(state.atomVarLe(v, d.max), false))
            return this
        }

        /** Node [u] can no longer move to [target]. */
        fun lacks(u: Int, target: Int): Reason {
            val literal = if (state.undoLogging) {
                state.exclusionLiteral(succ[u], target.toLong(), state.undo.size)
            } else if (target.toLong() in state.rootDomains[succ[u]]) {
                Lit.make(state.atomVarEq(succ[u], target.toLong()), true)
            } else {
                Lit.NONE
            }
            if (literal != Lit.NONE) addLiteral(literal)
            return this
        }

        /** Every node of [inside] lacks every target outside it: no arc leaves the set. */
        fun closed(inside: BooleanArray): Reason {
            for (u in 0 until n) {
                if (!inside[u]) continue
                for (t in 0 until n) if (!inside[t] && t.toLong() !in state.intDomains[succ[u]]) lacks(u, t)
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

        /** The bound that keeps one node outside [excluded] from opting out, which the cycle must then visit. */
        fun mandatoryOutside(mandatory: BooleanArray, excluded: BooleanArray): Reason {
            var chosen = -1
            for (k in 0 until n) {
                if (!mandatory[k] || excluded[k]) continue
                if (chosen == -1 || cost(k) < cost(chosen)) chosen = k
            }
            check(chosen >= 0) { "no mandatory node outside the cycle" }
            val d = state.intDomains[succ[chosen]]
            return if (chosen < d.min) lower(succ[chosen]) else upper(succ[chosen])
        }

        // A node opted in by a root bound costs no literal.
        private fun cost(k: Int): Int {
            val v = succ[k]
            val d = state.intDomains[v]
            val root = state.rootDomains[v]
            return if (k < d.min) (if (d.min > root.min) 1 else 0) else (if (d.max < root.max) 1 else 0)
        }

        private fun addLiteral(literal: Int) {
            if (!literals.contains(literal)) literals.add(literal)
        }

        fun build(): IntArray? = if (literals.size == 0) null else literals.toIntArray()
    }

    /**
     * Necessary condition for the single sub-cycle: every node that cannot opt out (its own index is
     * no longer in `succ(i)`'s domain, so it must lie on the cycle) must reach, and be reached by,
     * every other such mandatory node over non-self candidate arcs — the cycle visits them all in
     * one strongly-connected loop. Optional nodes may serve as intermediate stops, so reachability
     * is taken over the full candidate graph. A correct sub-circuit never trips it.
     *
     * A mandatory node `m` the root cannot reach fails on the set `R` the root does reach: no arc leaves `R`, so
     * the cycle through the root stays inside it and misses `m`; the reason is the two nodes' opt-outs being gone
     * and every arc out of `R`. One that cannot reach the root mirrors it on the arcs into the root's ancestors.
     */
    private fun stronglyConnectedSubcircuit(state: PropagationState): Boolean {
        val mandatory = BooleanArray(n)
        var mandCount = 0
        var root = -1
        for (i in 0 until n) {
            if (i.toLong() !in state.intDomains[succ[i]]) {
                mandatory[i] = true
                mandCount++
                if (root < 0) root = i
            }
        }
        if (mandCount < 2) return true
        val rev = Array(n) { IntArrayList() }
        for (i in 0 until n) {
            state.intDomains[succ[i]].values.forEach { j ->
                if (j != i.toLong() && j in 0 until n) rev[j.toInt()].add(i)
            }
        }
        for (forward in booleanArrayOf(true, false)) {
            val reached = reachable(state, root, forward, rev)
            val missed = (0 until n).firstOrNull { mandatory[it] && !reached[it] } ?: continue
            val reason = Reason(state).lacks(root, root).lacks(missed, missed)
            return fail(if (forward) reason.closed(reached) else reason.unentered(reached))
        }
        return true
    }

    // The nodes [root] reaches over non-self candidate arcs ([forward]), or that reach it.
    private fun reachable(
        state: PropagationState,
        root: Int,
        forward: Boolean,
        rev: Array<IntArrayList>,
    ): BooleanArray {
        val seen = BooleanArray(n)
        val stack = IntArrayList()
        seen[root] = true
        stack.add(root)
        while (stack.size > 0) {
            val u = stack[stack.size - 1]
            stack.truncateTo(stack.size - 1)
            if (forward) {
                state.intDomains[succ[u]].values.forEach { t ->
                    val v = t.toInt()
                    if (t in 0 until n && v != u && !seen[v]) {
                        seen[v] = true
                        stack.add(v)
                    }
                }
            } else {
                val preds = rev[u]
                for (k in 0 until preds.size) {
                    val v = preds[k]
                    if (!seen[v]) {
                        seen[v] = true
                        stack.add(v)
                    }
                }
            }
        }
        return seen
    }
}
