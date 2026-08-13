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
 * decides that literal, which is how a reified row participates. [zeroNode] is the extra vertex standing
 * for the constant 0, so a one-variable row is a difference like any other.
 */
internal class DifferenceFragment(
    val edges: List<DifferenceEdge>,
    val zeroNode: Int,
    /** Number of graph vertices: every integer variable plus [zeroNode]. */
    val numNodes: Int,
    /** Factors whose whole content became edges, so a consumer knows what the graph already covers. */
    val absorbedFactors: IntArray,
) {
    /** A graph over [edges], for a consumer that wants to run a cycle search over the whole set. */
    fun graph(): DifferenceGraph {
        val g = DifferenceGraph(numNodes)
        for (e in edges) g.addEdge(e.source, e.target, e.bound)
        return g
    }
}

/**
 * Collect [factors]' difference constraints. Returns `null` only when there are none worth a graph.
 *
 * A [Linear] row contributes unconditional edges; a [ReifiedLinear] contributes edges guarded by its aux
 * literal, since it constrains exactly when that literal is true. Declared domains enter as differences
 * against the zero node so the graph is never weaker than the model. Any other factor is left alone.
 */
internal fun differenceFragmentOf(
    factors: Array<Factor>,
    numIntVars: Int,
    intDomains: Array<IntDomain>,
): DifferenceFragment? {
    val zero = numIntVars
    val edges = ArrayList<DifferenceEdge>()
    val absorbed = ArrayList<Int>()
    factors.forEachIndexed { id, f ->
        when (f) {
            is Linear ->
                if (f.isIntegerCore &&
                    appendDifferenceEdges(f.vars, f::coeff, f.op, f.bound, zero, DifferenceEdge.ALWAYS, edges)
                ) {
                    absorbed.add(id)
                }

            is ReifiedLinear ->
                // The row holds when the aux is true, so the edges carry that literal as their guard.
                appendDifferenceEdges(
                    f.vars,
                    f::coeff,
                    f.op,
                    f.bound,
                    zero,
                    Lit.make(f.auxBoolVar, true),
                    edges,
                )

            else -> Unit
        }
    }
    if (edges.isEmpty()) return null
    for (v in 0 until numIntVars) {
        val d = intDomains[v]
        if (d.max != Long.MAX_VALUE) edges.add(DifferenceEdge(zero, v, d.max))
        if (d.min != Long.MIN_VALUE) edges.add(DifferenceEdge(v, zero, -d.min))
    }
    return DifferenceFragment(edges, zero, numIntVars + 1, absorbed.toIntArray())
}
