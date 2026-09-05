package com.eignex.klause.lp.cut

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap

/**
 * At-most-one conflict structure read off a problem's factors: [adjacency] maps a Boolean variable to
 * the variables mutually exclusive with it — an edge per binary clause `¬a ∨ ¬b`, all pairs of an
 * at-most-one factor (`Cardinality(max = 1)`, unit-weight `Σ x ≤ 1` PseudoBoolean) over positive
 * literals — and [baseCliques] lists those at-most-one factors' member variables (pairwise adjacent by
 * construction). Global by construction: every edge is implied by a factor of the original problem.
 */
internal class ConflictGraph(val adjacency: MutableIntObjectMap<IntHashSet>, val baseCliques: List<IntArray>)

/** Builds the [ConflictGraph] the knapsack-lifting and clique separators share. */
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

            is PseudoBoolean -> if (factor.op == PbOp.LE && factor.bound == 1L && factor.weights.all { it == 1L }) {
                atMostOne(factor.literals)
            }

            else -> Unit
        }
    }
    return ConflictGraph(adj, baseCliques)
}
