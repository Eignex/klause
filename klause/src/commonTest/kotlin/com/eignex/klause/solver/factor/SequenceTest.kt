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

class SequenceTest {

    @Test
    fun `sequence enforces per-window bounds`() {
        // 6 vars ∈ {0,1}. Window k=3, S={1}, low=1, high=2.
        // Every 3-window has at least 1 and at most 2 ones.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(Sequence(low = 1, high = 2, k = 3,
                xs = intArrayOf(0, 1, 2, 3, 4, 5),
                values = intArrayOf(1))),
        )
        BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).take(20).forEach { sample ->
            for (w in 0..3) {
                val c = (w..w + 2).count { sample.ints[it] == 1 }
                assertTrue(c in 1..2, "window $w violated: ints=${sample.ints.toList()}")
            }
        }
    }

    @Test
    fun `sequence infeasible when bounds can't be met`() {
        // 4 vars ∈ {1}, all pinned. k=3, S={0}, low=1, high=3. No 0s anywhere → window
        // count = 0 < low = 1 → Unsat.
        val problem = Problem(
            numBoolVars = 0, numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(Sequence(low = 1, high = 3, k = 3,
                xs = intArrayOf(0, 1, 2, 3),
                values = intArrayOf(0))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
