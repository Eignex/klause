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

class ValuePrecedeTest {

    @Test
    fun `value_precede holds when first s comes before first t`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2, 3))),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            val ints = sample.ints.toList()
            val firstS = ints.indexOf(1)
            val firstT = ints.indexOf(2)
            if (firstT >= 0) {
                assertTrue(
                    firstS >= 0 && firstS < firstT,
                    "value_precede violated: ints=$ints firstS=$firstS firstT=$firstT"
                )
            }
        }
    }

    @Test
    fun `t pinned before s is Unsat`() {
        // First var pinned to 2 (t); third var pinned to 1 (s). t appears at index 0,
        // s at index 2 → violation.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(2, 2), IntDomain(0, 2), IntDomain(1, 1)),
            factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
