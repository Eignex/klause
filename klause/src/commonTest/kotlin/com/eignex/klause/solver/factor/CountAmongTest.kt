package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CountAmongTest {

    @Test
    fun `count_eq detects exact count`() {
        // 4 xs ∈ [0..3], count of "= 1" = 2 → exactly 2 ones.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3),
                IntDomain(2, 2),
            ),
            factors = listOf(Count(xs = intArrayOf(0, 1, 2, 3), v = 1, op = Count.Op.Eq, n = 4)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val ones = (0..3).count { sat.assignment.ints[it] == 1 }
        assertEquals(2, ones, "expected 2 ones; got ${sat.assignment.ints.toList()}")
    }

    @Test
    fun `count_le tally`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4),
                IntDomain(3, 3),
            ),
            factors = listOf(Count(xs = intArrayOf(0, 1, 2, 3), v = 2, op = Count.Op.Le, n = 4)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val le2 = (0..3).count { sat.assignment.ints[it] <= 2 }
        assertEquals(3, le2)
    }

    @Test
    fun `count_ge tally with var n`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4),
                IntDomain(0, 4),
            ),
            factors = listOf(
                Count(xs = intArrayOf(0, 1, 2, 3), v = 3, op = Count.Op.Ge, n = 4),
                Count(xs = intArrayOf(0, 1, 2, 3), v = 3, op = Count.Op.Ge, n = 4),  // dup ok
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val ge3 = (0..3).count { sat.assignment.ints[it] >= 3 }
        assertEquals(sat.assignment.ints[4], ge3)
    }

    @Test
    fun `among tallies membership in a value set`() {
        // 4 xs ∈ [0..5]; among in {1, 3, 5} must equal 2.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5),
                IntDomain(2, 2),
            ),
            factors = listOf(Among(n = 4, xs = intArrayOf(0, 1, 2, 3), values = intArrayOf(1, 3, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val inSet = (0..3).count { sat.assignment.ints[it] in setOf(1, 3, 5) }
        assertEquals(2, inSet)
    }
}
