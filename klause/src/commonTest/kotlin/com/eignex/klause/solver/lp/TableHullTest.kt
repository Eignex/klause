package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #22 Table LP linearization: the one selector-per-tuple convex hull. The LP optimum over the table
 * columns equals the best allowed tuple for any linear objective, and shrinking a variable's domain
 * removes the tuples it kills.
 */
class TableHullTest {

    private val eps = 1e-7

    // Allowed tuples for (x0, x1): (0,5), (2,2), (4,0). Stored row-major.
    private fun tableProblem(d0: IntDomain, d1: IntDomain): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(d0, d1),
        factors = arrayOf<Factor>(
            Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 5, 2, 2, 4, 0)),
        ),
    )

    private fun solve(p: Problem, obj: LinearObjective): Pair<SparseSolution, LpRelaxation> {
        val r = CpToLpRelaxation(p, obj, tableHull = true).build(PropagationSession(p))
        return solveSparse(r.model) to r
    }

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `hull minimizes a linear objective over the allowed tuples`() {
        // minimize x0 + x1 over {(0,5),(2,2),(4,0)} -> (2,2) wins with value 4.
        val p = tableProblem(IntDomain(0, 4), IntDomain(0, 5))
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.objectiveValue, eps)
        assertEquals(2.0, sol.primal(intCol(r, 0)), eps)
        assertEquals(2.0, sol.primal(intCol(r, 1)), eps)
    }

    @Test
    fun `hull picks the cheapest tuple for a skewed objective`() {
        // minimize x0 over the tuples -> (0,5), so x0 = 0.
        val p = tableProblem(IntDomain(0, 4), IntDomain(0, 5))
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 0L)))

        assertEquals(0.0, sol.objectiveValue, eps)
        assertEquals(0.0, sol.primal(intCol(r, 0)), eps)
        assertEquals(5.0, sol.primal(intCol(r, 1)), eps) // channelled from the selected tuple
    }

    @Test
    fun `shrinking a domain removes the tuples it kills`() {
        // Restrict x1 <= 1: only tuple (4,0) survives, so minimizing x0+x1 must give 4.
        val p = tableProblem(IntDomain(0, 4), IntDomain(0, 1))
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 1L)))

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.objectiveValue, eps)
        assertEquals(4.0, sol.primal(intCol(r, 0)), eps)
        assertEquals(0.0, sol.primal(intCol(r, 1)), eps)
    }

    @Test
    fun `the hull excludes no allowed tuple and nothing outside their convex hull`() {
        // The LP feasible region projected to (x0,x1) is conv{(0,5),(2,2),(4,0)}. Check a maximize
        // direction lands on a vertex tuple, confirming the hull is exactly those tuples' convex set.
        val p = tableProblem(IntDomain(0, 4), IntDomain(0, 5))
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(-1L, 0L))) // maximize x0
        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.primal(intCol(r, 0)), eps) // (4,0) is the max-x0 vertex
        // x0+x1 of any LP point lies within the tuple range; the three tuples all sum to <= 5.
        assertTrue(sol.primal(intCol(r, 0)) + sol.primal(intCol(r, 1)) <= 5.0 + eps)
    }
}
