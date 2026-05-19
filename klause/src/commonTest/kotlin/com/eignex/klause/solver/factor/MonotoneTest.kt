package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MonotoneTest {

    private fun simple(direction: Monotone.Direction, strict: Boolean): Problem {
        return Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 5) },
            factors = listOf(Monotone(intArrayOf(0, 1, 2, 3), direction, strict)),
        )
    }

    @Test
    fun `strictly increasing finds an ascending chain`() {
        val r = BacktrackSolver(simple(Monotone.Direction.Increasing, strict = true))
            .solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val v = sat.assignment.ints
        for (i in 0 until 3) assertTrue(v[i] < v[i + 1], "not strict: $v")
    }

    @Test
    fun `non-strict increasing accepts equal consecutive values`() {
        val r = BacktrackSolver(simple(Monotone.Direction.Increasing, strict = false))
            .solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val v = sat.assignment.ints
        for (i in 0 until 3) assertTrue(v[i] <= v[i + 1], "not monotone: $v")
    }

    @Test
    fun `strictly decreasing chain`() {
        val r = BacktrackSolver(simple(Monotone.Direction.Decreasing, strict = true))
            .solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val v = sat.assignment.ints
        for (i in 0 until 3) assertTrue(v[i] > v[i + 1], "not strict desc: $v")
    }

    @Test
    fun `infeasible strict chain reports Unsat`() {
        // 4 vars all in [0..2]: 4 strictly-increasing values needed from a 3-value pool.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 2) },
            factors = listOf(Monotone(intArrayOf(0, 1, 2, 3), Monotone.Direction.Increasing, strict = true)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `propagate chains bounds without finding model first`() {
        // Mixed-domain test: v0 in [3, 5], v1, v2 in [0, 5]. With Increasing strict:
        // v1.min >= 4, v2.min >= 5. Solving should still find a model.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(3, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = listOf(Monotone(intArrayOf(0, 1, 2), Monotone.Direction.Increasing, strict = true)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.ints[0] < sat.assignment.ints[1])
        assertTrue(sat.assignment.ints[1] < sat.assignment.ints[2])
    }
}
