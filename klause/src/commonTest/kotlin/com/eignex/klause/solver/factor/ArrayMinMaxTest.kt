package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.arithmetic.ArrayMinMax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ArrayMinMaxTest {

    @Test
    fun `array maximum returns the max element`() {
        // r = max(v0, v1, v2). All ∈ [0..3]. Pin v0=3, v1=1, v2=2: r must be 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 1), IntDomain(2, 2), IntDomain(0, 5)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(3, sat.assignment.ints[3])
    }

    @Test
    fun `array minimum returns the min element`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(3, 3), IntDomain(1, 1), IntDomain(2, 2), IntDomain(0, 5)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = false)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[3])
    }

    @Test
    fun `array maximum propagation tightens result against xs domains`() {
        // result ∈ [0..10], xs[i] ∈ [0..5]. propagate should tighten result.max to 5.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 10)),
            factors = arrayOf<Factor>(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        // Result must equal max(xs); since xs.max ≤ 5, so does result.
        val resVal = sat.assignment.ints[3]
        val xsVals = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2])
        assertEquals(xsVals.max(), resVal)
    }
}
