package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle.Outcome
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SymmetricAllDifferentPropagatorTest {

    @Test
    fun `a singleton should force its mirror`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(2, 2), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(SymmetricAllDifferent(intArrayOf(0, 1, 2, 3))),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(0, sat.assignment.ints[2])
    }

    @Test
    fun `symmetric alldifferent reasons should cover mirror deductions and conflicts`() {
        val rng = Random(0x5A11)
        repeat(2) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, n - 1L) },
                factors = arrayOf<Factor>(SymmetricAllDifferent(IntArray(n) { it })),
            )
            val (first, second, third) = (0 until n).shuffled(rng)
            val outcome = PropagationReasonOracle.assertReasonsImply(problem, "symmetric-alldiff#$iter") { state ->
                state.setInt(first, second.toLong()) &&
                    (iter % 2 == 0 || state.setInt(second, third.toLong()))
            }

            assertEquals(if (iter % 2 == 0) Outcome.DEDUCTION else Outcome.CONFLICT, outcome)
        }
    }
}
