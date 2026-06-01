package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArgMinMaxTest {

    @Test
    fun `arg_max with pinned xs identifies maximum index`() {
        // xs = [1, 3, 2, 3]. arg_max should be 1 (first occurrence of max 3, lex tiebreak).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(3, 3), IntDomain(2, 2), IntDomain(3, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(ArgMinMax(idx = 4, xs = intArrayOf(0, 1, 2, 3), max = true)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[4])
    }

    @Test
    fun `arg_min with pinned xs`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(IntDomain(5, 5), IntDomain(1, 1), IntDomain(3, 3), IntDomain(1, 1), IntDomain(0, 3)),
            factors = arrayOf<Factor>(ArgMinMax(idx = 4, xs = intArrayOf(0, 1, 2, 3), max = false)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[4])
    }

    @Test
    fun `arg_max with 1-based offset`() {
        // Same data, but idx in 1-based domain. arg_max should be 2 (position 2, 1-based).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(3, 3), IntDomain(2, 2), IntDomain(3, 3), IntDomain(1, 4)),
            factors = arrayOf<Factor>(ArgMinMax(idx = 4, xs = intArrayOf(0, 1, 2, 3), max = true, indexOffset = 1)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(2, sat.assignment.ints[4])
    }
}
