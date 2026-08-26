package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Element
import com.eignex.klause.lp.LpSolution
import com.eignex.klause.lp.LpVerdict
import com.eignex.klause.lp.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #22 Element LP linearization: the one-hot selector model for a constant array. It is the exact
 * convex hull, so the LP bound on `result` equals the true min/max selectable entry, and the index
 * channel ties `idx` to the selected position.
 */
class CpToLpRelaxationElementHullTest {

    private val eps = 1e-7

    private fun solve(p: Problem, obj: LinearObjective): Pair<LpSolution, LpRelaxation> {
        val r = CpToLpRelaxation(p, obj, elementHull = true).build(PropagationSession(p))
        return solveLp(r.model) to r
    }

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `constant array hull bounds result by the min selectable entry`() {
        // result = arr[idx], arr = [7, 3, 9, 5] (0-based), idx in 0..3, minimize result.
        // The hull lets the LP pick the cheapest entry: result = 3.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2, // 0 = idx, 1 = result
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 0),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L)))

        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertEquals(3.0, sol.objectiveValue, eps)
        assertEquals(3.0, sol.primal(intCol(r, 1)), eps)
    }

    @Test
    fun `restricting the index restricts the hull bound`() {
        // Same array but idx pinned to {2,3}: cheapest reachable entry is arr[3] = 5.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(2, 3), IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 0),
            ),
        )
        val (sol, _) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L)))

        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertEquals(5.0, sol.objectiveValue, eps)
    }

    @Test
    fun `maximizing selects the dearest entry`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 0),
            ),
        )
        // maximize result <=> minimize -result; hull caps it at the largest entry 9.
        val (sol, _) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, -1L)))

        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertEquals(-9.0, sol.objectiveValue, eps)
    }

    @Test
    fun `index channel ties idx to the selected position`() {
        // 1-based index (MiniZinc default): arr=[7,3,9,5], minimize result -> picks position of 3,
        // which is 0-based p=1, i.e. idx = 2.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 4), IntDomain(0, 20)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 1),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L)))

        assertEquals(3.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(intCol(r, 0)), eps) // idx points at the cheapest entry
    }

    @Test
    fun `variable array element builds a sound big-M hull`() {
        // Variable-array Element linearizes to a big-M selector hull: it adds selector columns plus
        // one-hot/index/big-M rows, and the LP bound on `result` is a sound relaxation bound — never
        // above the true integer optimum.
        // arr = [v0∈[4,6], v1∈[1,2], v2∈[8,9]], idx∈{0,1,2}, minimize result ⇒ integer min = 1 (idx=1).
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 5, // 0=idx, 1=result, 2..4 = arr vars
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 100),
                IntDomain(4, 6),
                IntDomain(1, 2),
                IntDomain(8, 9),
            ),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 0),
            ),
        )
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(0L, 1L, 0L, 0L, 0L)))
        // The hull adds selector columns and the one-hot + index-channel + big-M rows.
        assertTrue(r.model.n > 1, "selector columns are added")
        assertTrue(r.model.m >= 3, "one-hot + index channel + per-position big-M rows")
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertTrue(sol.objectiveValue <= 1.0 + eps, "UNSOUND: LP min ${sol.objectiveValue} exceeds integer optimum 1")
    }
}
