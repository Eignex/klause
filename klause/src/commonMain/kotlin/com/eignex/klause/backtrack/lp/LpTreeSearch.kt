package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.dualSimplex
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/** One branch decision on the path from the root in [lbTreeSearch]: pin/bound [varId]. */
private class LbDecision(val isBool: Boolean, val varId: Int, val lower: Boolean, val bound: Long)

/** An open node in [lbTreeSearch]: its decisions from the root and the LP bound used to order it. */
private class LbNode(val decisions: List<LbDecision>, val bound: Double)

/**
 * Best-bound (best-first) tree-search subsolver. Explores the
 * branch-and-bound tree expanding the open node with the smallest LP relaxation bound first, diving
 * toward integer-feasible leaves to find good incumbents fast — the complement of depth-first search.
 * Each node re-derives a fresh session from its root decisions, solves the node LP for an ordering
 * bound and a fractional point, and branches on the most-fractional structural variable. A leaf whose
 * LP point is integral is realized through [pinToward] (propagation-checked) into an incumbent.
 *
 * Purely a primal heuristic: it returns only fully-pinned, propagation-feasible incumbents (the caller
 * re-evaluates), and dropping a node only forgoes exploring it — so this never affects soundness or the
 * optimum, exactly like the feasibility pump. Bounded by [LB_TREE_BUDGET] node expansions and a
 * frontier cap; returns the best incumbent found, or null. Lives in the search layer because it realizes
 * incumbents through [pinToward] (which snapshots the backtrack assignment).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
internal fun LpEngine.lbTreeSearch(objective: LinearObjective, cancellation: Cancellation): Sample? {
    val relaxer = lpRelaxer ?: return null
    var best: Sample? = null
    var bestObj = Double.POSITIVE_INFINITY
    val frontier = ArrayList<LbNode>()
    frontier.add(LbNode(emptyList(), Double.NEGATIVE_INFINITY))
    var expansions = 0
    while (frontier.isNotEmpty() && expansions < LB_TREE_BUDGET && !cancellation()) {
        var bi = 0 // pop the open node with the smallest bound (best-first)
        for (i in 1 until frontier.size) if (frontier[i].bound < frontier[bi].bound) bi = i
        val node = frontier.removeAt(bi)
        if (node.bound >= bestObj) continue // already dominated by the incumbent
        expansions++
        val session = PropagationSession(problem)
        if (session.isUnsatAtRoot) continue
        if (node.decisions.any { applyLbDecision(session, it) is PropagationResult.Unsat }) continue
        // The cut-free base relaxation suffices for this primal dive — the harvested cuts only tighten the
        // ordering bound, never the feasibility of a realized incumbent (which pinToward re-checks).
        val relaxation = nodeRelaxation(relaxer, session)
        if (relaxation.model.n == 0) continue
        val result = dualSimplex(relaxation.model, cancellation).solve() ?: continue // infeasible / unknown ⇒ drop
        if (result.objective >= bestObj) continue
        val frac = mostFractionalCol(relaxation, result.primal)
        if (frac == null) {
            // Integer LP point: realize it as an incumbent (pinToward propagates + checks feasibility).
            pinToward(session, relaxation) { col -> if (col in result.primal.indices) result.primal[col] else null }
                ?.let { s ->
                    val obj = objective.evaluate(s)
                    if (obj < bestObj) {
                        best = s
                        bestObj = obj
                    }
                }
            continue
        }
        val (v, isBool, f) = frac
        if (isBool) {
            frontier.add(LbNode(node.decisions + LbDecision(true, v, false, 0L), result.objective))
            frontier.add(LbNode(node.decisions + LbDecision(true, v, false, 1L), result.objective))
        } else {
            frontier.add(LbNode(node.decisions + LbDecision(false, v, false, floor(f).toLong()), result.objective))
            frontier.add(LbNode(node.decisions + LbDecision(false, v, true, ceil(f).toLong()), result.objective))
        }
        while (frontier.size > LB_TREE_FRONTIER_CAP) { // bound memory: drop the worst (highest-bound) node
            var wi = 0
            for (i in 1 until frontier.size) if (frontier[i].bound > frontier[wi].bound) wi = i
            frontier.removeAt(wi)
        }
    }
    return best
}

/** Apply an [LbDecision] to [session], returning the propagation result (Unsat ⇒ the node is dead). */
private fun applyLbDecision(session: PropagationSession, d: LbDecision): PropagationResult = when {
    d.isBool -> session.implyBool(d.varId, d.bound == 1L)
    d.lower -> session.implyIntAtLeast(d.varId, d.bound)
    else -> session.implyIntAtMost(d.varId, d.bound)
}

/** The structural column whose LP value is furthest from an integer, as `(varId, isBool, value)`, or
 *  null when every CP-backed structural column is integral (an integer LP point). */
private fun mostFractionalCol(relaxation: LpRelaxation, primal: DoubleArray): Triple<Int, Boolean, Double>? {
    var best: Triple<Int, Boolean, Double>? = null
    var bestFrac = LB_TREE_FRAC_TOL
    for (col in relaxation.colVarId.indices) {
        val v = relaxation.colVarId[col]
        if (v < 0 || col >= primal.size) continue
        val lp = primal[col]
        val frac = abs(lp - round(lp))
        if (frac > bestFrac) {
            bestFrac = frac
            best = Triple(v, relaxation.colIsBool[col], lp)
        }
    }
    return best
}

/** Node-expansion budget for the best-bound tree-search subsolver (each expansion is one node LP). */
private const val LB_TREE_BUDGET = 256

/** Cap on the best-bound search frontier; the highest-bound nodes are dropped past it (memory bound). */
private const val LB_TREE_FRONTIER_CAP = 512

/** A structural column's LP value within this of an integer is treated as integral (no branch). */
private const val LB_TREE_FRAC_TOL = 1e-6
