package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.factor.circuit.internals.buildSuccWatches
import com.eignex.klause.factor.circuit.internals.cpGateShouldSkip
import com.eignex.klause.factor.circuit.internals.shaveClaimedFromEndpoints
import com.eignex.klause.factor.circuit.internals.tightenSuccToRange
import com.eignex.klause.factor.circuit.internals.walkPredChain
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.IntArrayList

/** CP implementation for [Subcircuit]: propagation of the optional-cycle constraint over successor vars. */
internal class SubcircuitPropagator(private val succ: IntArray, private val n: Int) : Propagator {

    override val initialIntEventWatches: IntArray = buildSuccWatches(succ)
    override val consumesIntEventDelta: Boolean = true

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, succ, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (state.cpGateShouldSkip(factorId)) return true
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
            val c = walkPredChain(pred, i, n)
            val start = c.head
            val chainNodes = c.length
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
        if (!stronglyConnectedSubcircuit(state)) return false
        return true
    }

    /**
     * Necessary condition for the single sub-cycle: every node that cannot opt out (its own index is
     * no longer in `succ(i)`'s domain, so it must lie on the cycle) must reach, and be reached by,
     * every other such mandatory node over non-self candidate arcs — the cycle visits them all in
     * one strongly-connected loop. Optional nodes may serve as intermediate stops, so reachability
     * is taken over the full candidate graph. A correct sub-circuit never trips it.
     */
    private fun stronglyConnectedSubcircuit(state: PropagationState): Boolean {
        val mandatory = BooleanArray(n)
        var mandCount = 0
        var root = -1
        for (i in 0 until n) {
            if (i !in state.intDomains[succ[i]]) {
                mandatory[i] = true
                mandCount++
                if (root < 0) root = i
            }
        }
        if (mandCount < 2) return true
        val rev = Array(n) { IntArrayList() }
        for (i in 0 until n) {
            state.intDomains[succ[i]].forEach { j -> if (j != i && j in 0 until n) rev[j].add(i) }
        }
        return reachesAllMandatory(state, root, mandatory, mandCount, forward = true, rev = rev) &&
            reachesAllMandatory(state, root, mandatory, mandCount, forward = false, rev = rev)
    }

    private fun reachesAllMandatory(
        state: PropagationState,
        root: Int,
        mandatory: BooleanArray,
        mandCount: Int,
        forward: Boolean,
        rev: Array<IntArrayList>,
    ): Boolean {
        val seen = BooleanArray(n)
        val stack = IntArrayList()
        seen[root] = true
        stack.add(root)
        var reached = 1
        while (!stack.isEmpty()) {
            val u = stack[stack.size - 1]
            stack.removeAt(stack.size - 1)
            if (forward) {
                state.intDomains[succ[u]].forEach { v ->
                    if (v != u && v in 0 until n && !seen[v]) {
                        seen[v] = true
                        if (mandatory[v]) reached++
                        stack.add(v)
                    }
                }
            } else {
                val preds = rev[u]
                for (idx in 0 until preds.size) {
                    val v = preds[idx]
                    if (!seen[v]) {
                        seen[v] = true
                        if (mandatory[v]) reached++
                        stack.add(v)
                    }
                }
            }
        }
        return reached == mandCount
    }
}
