package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SymmetricAllDifferentPropagatorTest {

    @Test
    fun `0-based self-inverse permutation`() {
        // 4 vars, 0-based. xs[xs[i]] = i. Possible solutions: identity, single swap, two swaps.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(SymmetricAllDifferent(intArrayOf(0, 1, 2, 3))),
        )
        BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L)).take(40).forEach { sample ->
            for (i in 0..3) {
                val v = sample.ints[i].toInt()
                assertTrue(sample.ints[v] == i.toLong(), "self-inverse violated at $i: ints=${sample.ints.toList()}")
            }
        }
    }

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
}
