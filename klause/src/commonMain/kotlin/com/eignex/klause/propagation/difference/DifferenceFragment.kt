package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit

/**
 * The difference constraints a model contains, gathered across its factors.
 *
 * Deliberately NOT all-or-nothing. An earlier version demanded that *every* factor be a difference row,
 * which recognised 0 of 30 real QF_IDL instances: they always carry Boolean structure, so one clause
 * disqualified the whole model. Collecting per factor composes with that structure instead — the clauses
 * and any general linear rows stay exactly as they are, and whatever difference rows exist become a graph.
 *
 * [edges] hold the constraints; an edge guarded by a Boolean literal only constrains once the search
 * decides that literal, which is how a reified row participates. An endpoint of [ZERO] is the constant 0,
 * so a one-variable row is a difference like any other.
 */
internal class DifferenceFragment(val edges: List<DifferenceEdge>) {
    /** The integer variables the edges mention, ascending. [ZERO] is not among them. */
    val nodes: IntArray = run {
        val seen = HashSet<Int>()
        for (e in edges) {
            if (e.source != ZERO) seen.add(e.source)
            if (e.target != ZERO) seen.add(e.target)
        }
        seen.toIntArray().sortedArray()
    }

    /** Graph vertices: one per entry of [nodes], plus a final one for [ZERO]. */
    val numNodes: Int get() = nodes.size + 1

    /** The vertex standing for the constant 0. */
    val zeroNode: Int get() = nodes.size

    /**
     * Vertex index of an edge endpoint. The graph is numbered over [nodes] alone rather than over every
     * integer variable, so a model with a handful of difference rows among many variables does not pay
     * for a graph the size of the model.
     */
    fun nodeOf(endpoint: Int): Int = if (endpoint == ZERO) zeroNode else indexOfSorted(nodes, endpoint)

    /**
     * Whether a graph over these edges could hold a potential, which is what every deduction rests on.
     * Answers before the factor is built, so a system that could only decline every fire is never posted.
     */
    fun carriesAPotential(): Boolean {
        var maxAbs = 0L
        for (e in edges) {
            val a = if (e.bound < 0L) -e.bound else e.bound
            if (a > maxAbs) maxAbs = a
        }
        return numNodes > 0 && maxAbs <= IncrementalDifferenceGraph.weightRoom(numNodes)
    }

    /** A graph over [edges], for a consumer that wants to run a cycle search over the whole set. */
    fun graph(): DifferenceGraph {
        val g = DifferenceGraph(numNodes)
        for (e in edges) g.addEdge(nodeOf(e.source), nodeOf(e.target), e.bound)
        return g
    }

    internal companion object {
        /** Endpoint value standing for the constant 0, so a one-variable row is still a difference. */
        const val ZERO: Int = -1
    }
}

/** Position of [value] in the ascending [sorted], which must contain it. */
internal fun indexOfSorted(sorted: IntArray, value: Int): Int {
    var lo = 0
    var hi = sorted.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = sorted[mid]
        when {
            v < value -> lo = mid + 1
            v > value -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}

/**
 * Collect [factors]' difference constraints. Returns `null` only when there are none worth a graph.
 *
 * A [Linear] row contributes unconditional edges; a [ReifiedLinear] contributes edges guarded by its aux
 * literal, since it constrains exactly when that literal is true. Declared domains enter as differences
 * against the zero node, marked [DifferenceEdge.domainBound] so a consumer can tell a stated row from a
 * column's own range. Any other factor is left alone.
 */
internal fun differenceFragmentOf(
    factors: Array<Factor>,
    numIntVars: Int,
    intDomains: Array<IntDomain>,
): DifferenceFragment? {
    val zero = DifferenceFragment.ZERO
    val edges = ArrayList<DifferenceEdge>()
    factors.forEach { f ->
        when (f) {
            is Linear ->
                if (f.isIntegerCore) {
                    appendDifferenceEdges(f.vars, f::coeff, f.op, f.bound, zero, DifferenceEdge.ALWAYS, edges)
                }

            is ReifiedLinear ->
                // The aux is an equivalence, so both polarities constrain: the row under a true aux, its
                // integer negation under a false one. A wide row's Long coefficients are placeholders, so
                // its shape cannot be read here at all.
                if (!f.wide) {
                    appendDifferenceEdges(f.vars, f::coeff, f.op, f.bound, zero, Lit.make(f.auxBoolVar, true), edges)
                    appendNegatedDifferenceEdges(
                        f.vars,
                        f::coeff,
                        f.op,
                        f.bound,
                        zero,
                        Lit.make(f.auxBoolVar, false),
                        edges,
                    )
                }

            else -> Unit
        }
    }
    if (edges.isEmpty()) return null
    val mentioned = HashSet<Int>()
    for (e in edges) {
        if (e.source != zero) mentioned.add(e.source)
        if (e.target != zero) mentioned.add(e.target)
    }
    for (v in mentioned.toIntArray().sortedArray()) {
        if (v >= numIntVars) continue
        val d = intDomains[v]
        if (d.max != Long.MAX_VALUE) edges.add(DifferenceEdge(zero, v, d.max, domainBound = true))
        if (d.min != Long.MIN_VALUE) edges.add(DifferenceEdge(v, zero, -d.min, domainBound = true))
    }
    return DifferenceFragment(edges)
}
