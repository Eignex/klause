package com.eignex.klause.propagation.difference

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray

/**
 * Joint propagation of a [DifferenceSystem]: the asserted edges are consistent exactly when their graph
 * holds no negative cycle.
 *
 * An edge is asserted when it is unconditional or its guard literal is currently true. A cycle among the
 * asserted edges sums their constraints to `0 ≤ w` with `w < 0`, so the guards on that cycle cannot all
 * hold at once — which is precisely the clause handed to conflict analysis.
 */
internal class DifferenceSystemPropagator(edges: List<DifferenceEdge>) : Propagator {

    /** Compact vertex numbering over the variables the edges mention, with the constant node last. */
    private val nodes: IntArray
    private val zeroNode: Int
    private val guards: IntArray
    private val graph: DifferenceGraph
    private val active: BooleanArray

    /** The explanation for the cycle [propagate] last found, read back by [conflictReason]. */
    private var reason: IntArray? = null

    init {
        val seen = HashSet<Int>()
        for (e in edges) {
            if (e.source != DifferenceFragment.ZERO) seen.add(e.source)
            if (e.target != DifferenceFragment.ZERO) seen.add(e.target)
        }
        nodes = seen.toIntArray().sortedArray()
        zeroNode = nodes.size
        graph = DifferenceGraph(nodes.size + 1)
        guards = IntArray(edges.size)
        edges.forEachIndexed { i, e ->
            graph.addEdge(nodeOf(e.source), nodeOf(e.target), e.bound)
            guards[i] = e.guard
        }
        active = BooleanArray(edges.size)
    }

    private fun nodeOf(endpoint: Int): Int =
        if (endpoint == DifferenceFragment.ZERO) zeroNode else indexOfSorted(nodes, endpoint)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        reason = null
        for (i in guards.indices) {
            val g = guards[i]
            active[i] = g == DifferenceEdge.ALWAYS || isTrue(state, g)
        }
        val cycle = graph.negativeCycle(active) ?: return true
        reason = explain(state, cycle)
        return false
    }

    /** Whether [lit] is currently pinned to true, so its edge is asserted. */
    private fun isTrue(state: PropagationState, lit: Int): Boolean {
        val v = Lit.variable(lit)
        val value = state.boolValues[v] ?: return false
        return Lit.evaluate(lit, value)
    }

    /**
     * The clause blocking [cycle]: the negation of every guard on it. Each such literal is false now (its
     * guard holds, or the edge would not be asserted), which is what conflict analysis requires of a seed.
     * An all-unconditional cycle has no guards and yields an empty clause — the system is unsatisfiable
     * outright, and the empty seed says exactly that.
     */
    private fun explain(state: PropagationState, cycle: IntArray): IntArray {
        val lits = LinkedHashSet<Int>()
        for (e in cycle) {
            val g = guards[e]
            if (g == DifferenceEdge.ALWAYS) continue
            val v = Lit.variable(g)
            lits.add(Lit.make(v, !state.boolValueAt(v)))
        }
        return if (lits.isEmpty()) EmptyIntArray else lits.toIntArray()
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = reason
}
