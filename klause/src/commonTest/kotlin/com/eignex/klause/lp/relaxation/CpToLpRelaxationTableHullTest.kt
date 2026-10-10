package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpSolution
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #22 Table LP linearization: the one selector-per-tuple convex hull. The LP optimum over the table
 * columns equals the best allowed tuple for any linear objective, and shrinking a variable's domain
 * removes the tuples it kills.
 */
class CpToLpRelaxationTableHullTest {

    private val eps = 1e-7

    // Allowed tuples for (x0, x1): (0,5), (2,2), (4,0). Stored row-major.
    private fun tableProblem(d0: IntDomain, d1: IntDomain): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(d0, d1),
        factors = arrayOf<Factor>(
            Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 5, 2, 2, 4, 0)),
        ),
    )

    private fun solve(p: Problem, obj: LinearObjective): Pair<LpSolution, LpRelaxation> {
        val r = CpToLpRelaxation(p, obj, tableHull = true).build(PropagationSession(p))
        return solveLp(r.model) to r
    }

    private fun intCol(r: LpRelaxation, v: Int): Int {
        for (c in r.colVarId.indices) if (!r.colIsBool[c] && r.colVarId[c] == v) return c
        return -1
    }

    @Test
    fun `a column the model leaves open gets no tuple hull`() {
        // A root box screens the tuples, so an invented endpoint would drop tuples the table allows and
        // the one-hot rows would then refute them.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 5, 2, 2, 4, 0))),
            openIntHi = booleanArrayOf(false, true),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val hull = CpToLpRelaxation(p, obj, tableHull = true).build(PropagationSession(p))
        val bare = CpToLpRelaxation(p, obj, tableHull = false).build(PropagationSession(p))
        assertEquals(bare.model.m, hull.model.m, "no hull row over an open-sided column")
        assertEquals(bare.model.n, hull.model.n, "and no selector column")
    }

    @Test
    fun `hull minimizes a linear objective over the allowed tuples`() {
        // minimize x0 + x1 over {(0,5),(2,2),(4,0)} -> value 4, achieved on the (2,2)–(4,0) face (a tie),
        // so assert the invariant optimum and that the point sits on that optimal face (x0 + x1 = 4)
        // rather than a specific degenerate vertex (which the pivot path may pick either end of).
        val p = tableProblem(IntDomain(0, 4), IntDomain(0, 5))
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.objectiveValue, eps)
        assertEquals(4.0, sol.primal(intCol(r, 0)) + sol.primal(intCol(r, 1)), eps)
    }

    @Test
    fun `shrinking a domain removes the tuples it kills`() {
        // Restrict x1 <= 1: only tuple (4,0) survives, so minimizing x0+x1 must give 4.
        val p = tableProblem(IntDomain(0, 4), IntDomain(0, 1))
        val (sol, r) = solve(p, LinearObjective(intCoefficients = longArrayOf(1L, 1L)))

        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(4.0, sol.objectiveValue, eps)
        assertEquals(4.0, sol.primal(intCol(r, 0)), eps)
        assertEquals(0.0, sol.primal(intCol(r, 1)), eps)
    }

}
