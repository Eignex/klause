package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.permitsLongPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntObjectMap

// Edges come from binary clauses `¬a ∨ ¬b` and at-most-one factors over positive literals; baseCliques lists
// those factors' members. Every edge is implied by an original factor, so the graph is global.
internal class ConflictGraph(val adjacency: MutableIntObjectMap<IntHashSet>, val baseCliques: List<IntArray>)

internal fun conflictGraph(problem: Problem): ConflictGraph {
    val adj = MutableIntObjectMap<IntHashSet>()
    fun edge(a: Int, b: Int) {
        if (a == b) return
        adj.getOrPut(a) { IntHashSet() }.add(b)
        adj.getOrPut(b) { IntHashSet() }.add(a)
    }

    val baseCliques = ArrayList<IntArray>()
    fun atMostOne(literals: IntArray) {
        if (literals.size < 2 || literals.any { !Lit.isPositive(it) }) return
        val vars = IntArray(literals.size) { Lit.variable(literals[it]) }
        for (i in vars.indices) for (j in i + 1 until vars.size) edge(vars[i], vars[j])
        baseCliques.add(vars)
    }
    for (factor in problem.factors) {
        when (factor) {
            is Clause -> if (factor.literals.size == 2 && factor.literals.none { Lit.isPositive(it) }) {
                edge(Lit.variable(factor.literals[0]), Lit.variable(factor.literals[1]))
            }

            is Cardinality -> if (factor.max == 1) atMostOne(factor.literals)

            is PseudoBoolean -> if (permitsLongPseudoBoolean(factor.constants) && factor.op == PbOp.LE &&
                factor.bound == 1L && factor.weights.all { it == 1L }
            ) {
                atMostOne(factor.literals)
            }

            else -> Unit
        }
    }
    return ConflictGraph(adj, baseCliques)
}
