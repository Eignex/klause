package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemberTest {

    @Test
    fun `member_int forces y to match one of xs`() {
        // xs pinned to (1, 3, 7); y ∈ [0..10] → y must be 1, 3, or 7.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(3, 3), IntDomain(7, 7), IntDomain(0, 10)),
            factors = arrayOf<Factor>(Member(xs = intArrayOf(0, 1, 2), y = 3)),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(10).forEach { sample ->
            assertTrue(sample.ints[3] in setOf(1, 3, 7),
                "y = ${sample.ints[3]} not in {1, 3, 7}")
        }
    }

    @Test
    fun `singleton y forces some xs to match`() {
        // y pinned to 5; xs are free. Some xs[i] must take value 5.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(5, 5)),
            factors = arrayOf<Factor>(Member(xs = intArrayOf(0, 1, 2), y = 3)),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(5 in sat.assignment.ints.take(3),
            "expected some xs to hold 5; got ${sat.assignment.ints.toList()}")
    }
}
