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
                    "value_precede violated: ints=$ints firstS=$firstS firstT=$firstT",
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

    @Test
    fun `forced t makes the sole earlier candidate take s`() {
        // x1 is pinned to t=2; only x0 can be s=1 before it ⟹ x0 must become 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(2, 2)),
            factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1))),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
        assertEquals(1, sat.assignment.ints[0], "sole pre-t candidate must take s")
    }

    @Test
    fun `holey domains prune premature t`() {
        // s=1 first becomes possible only at index 2 (x0,x1 ∈ {0,2} have a hole at 1), so t=2
        // is barred from indices 0..2 ⟹ x0=x1=0, x2=1. Exercises hole-aware rule 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(
                IntDomain(0, 2).excludeValue(1),
                IntDomain(0, 2).excludeValue(1),
                IntDomain(1, 2),
            ),
            factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2))),
        )
        val sat = assertIs<SolveResult.Sat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
        assertEquals(listOf(0, 0, 1), sat.assignment.ints.toList(), "premature t not pruned")
    }
}
