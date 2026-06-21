package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.global.Sort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SortPropagatorTest {

    @Test
    fun `sort matches sorted xs`() {
        // xs pinned to (3, 1, 2); ys = sorted(xs) = (1, 2, 3).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(3, 3),
                IntDomain(1, 1),
                IntDomain(2, 2),
                IntDomain(0, 9),
                IntDomain(0, 9),
                IntDomain(0, 9),
            ),
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[3])
        assertEquals(2, sat.assignment.ints[4])
        assertEquals(3, sat.assignment.ints[5])
    }

    @Test
    fun `sort with duplicates`() {
        // xs = (1, 2, 1) → ys = (1, 1, 2).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 1),
                IntDomain(2, 2),
                IntDomain(1, 1),
                IntDomain(0, 9),
                IntDomain(0, 9),
                IntDomain(0, 9),
            ),
            factors = arrayOf<Factor>(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(listOf(1, 1, 2), listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5]))
    }
}
