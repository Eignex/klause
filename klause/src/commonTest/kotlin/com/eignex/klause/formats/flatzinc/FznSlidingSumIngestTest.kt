package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `fzn_sliding_sum(low, up, seq, vs)` lowers to a pair of Linear range bounds per window.
 * Checks the windowed-sum semantics hold on the solved assignment.
 */
class FznSlidingSumIngestTest {

    @Test
    fun `fzn_sliding_sum bounds every window`() {
        val src = """
            var 0..4: x1; var 0..4: x2; var 0..4: x3; var 0..4: x4; var 0..4: x5;
            constraint fzn_sliding_sum(3, 5, 3, [x1, x2, x3, x4, x5]);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        val r = BacktrackSolver(program.problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val x = listOf("x1", "x2", "x3", "x4", "x5").map { sat.assignment.ints[program.intVarsByName.getValue(it)] }
        for (i in 0..x.size - 3) {
            val window = x[i] + x[i + 1] + x[i + 2]
            assertTrue(window in 3..5, "window at $i sums to $window, outside [3,5] (x=$x)")
        }
    }
}
