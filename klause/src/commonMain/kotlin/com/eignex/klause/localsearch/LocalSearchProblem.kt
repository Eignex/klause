package com.eignex.klause.localsearch

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.EmptyIntArray

/** Local-search-engine projection of an immutable [Problem]. */
class LocalSearchProblem(val problem: Problem) {
    val invariants: Array<out Invariant> = Array(problem.numFactors) { problem.factors[it].asInvariant() }

    val boolOccurrences: Array<IntArray> = invert(problem.numBoolVars) { it.boolVars }

    val intOccurrences: Array<IntArray> = invert(problem.numIntVars) { it.intVars }

    private inline fun invert(slots: Int, vars: (Factor) -> IntArray): Array<IntArray> {
        val counts = IntArray(slots)
        problem.factors.forEachIndexed { fid, factor ->
            if (invariants[fid] !== NoInvariant) for (v in vars(factor)) counts[v]++
        }
        val out = Array(slots) { if (counts[it] == 0) EmptyIntArray else IntArray(counts[it]) }
        val cursor = IntArray(slots)
        problem.factors.forEachIndexed { fid, factor ->
            if (invariants[fid] !== NoInvariant) for (v in vars(factor)) out[v][cursor[v]++] = fid
        }
        return out
    }
}
