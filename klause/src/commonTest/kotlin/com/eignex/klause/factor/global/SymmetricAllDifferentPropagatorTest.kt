package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
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
    fun `singleton forces mirror`() {
        // xs[0] pinned to 2 ⇒ xs[2] must be 0.
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
    fun `symmetric alldifferent deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x5A11)
        repeat(300) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, n - 1L) },
                factors = arrayOf<Factor>(SymmetricAllDifferent(IntArray(n) { it })),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "symmetric-alldiff#$iter") { state ->
                (0 until 6).all { state.excludeIntValue(rng.nextInt(n), rng.nextInt(n).toLong()) }
            }
        }
    }
}
