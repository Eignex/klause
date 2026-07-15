package com.eignex.klause.factor.arithmetic

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState

/**
 * Local-search invariant for [ComparisonClause]: violated iff no literal holds under the current
 * assignment. The graded degree is the smallest single-literal shortfall — the fewest units any one
 * variable would have to move for its literal to hold — so descent sees a gradient toward repairing
 * the clause through its cheapest literal.
 */
internal class ComparisonClauseInvariant(
    private val vars: IntArray,
    private val ops: Array<LinearOp>,
    private val consts: LongArray,
) : Invariant {

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        for (i in vars.indices) if (literalShortfall(state, i) == 0L) return false
        return true
    }

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        var best = Long.MAX_VALUE
        for (i in vars.indices) {
            val s = literalShortfall(state, i)
            if (s == 0L) return 0
            if (s < best) best = s
        }
        return best.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Units the variable of literal [i] must move for that literal to hold; `0` when it already does. */
    private fun literalShortfall(state: LocalSearchState, i: Int): Long {
        val x = state.assignment.intValue(vars[i])
        val c = consts[i]
        return when (ops[i]) {
            LinearOp.LE -> if (x <= c) 0L else x - c

            LinearOp.GE -> if (x >= c) 0L else c - x

            LinearOp.EQ -> if (x == c) {
                0L
            } else if (x > c) {
                x - c
            } else {
                c - x
            }

            LinearOp.NE -> if (x != c) 0L else 1L
        }
    }
}
