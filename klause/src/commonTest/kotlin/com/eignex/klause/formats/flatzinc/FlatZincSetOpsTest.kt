package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertIs

class FlatZincSetOpsTest {
    @Test
    fun `set intersection should forbid shared members outside the result universe`() {
        val results = listOf("" to "1..0", "var set of {1}: result;" to "result")
        for ((declaration, output) in results) {
            val program = parseFlatZinc(
                """
                var set of 1..2: a = {2};
                var set of 2..3: b = {2};
                $declaration
                constraint set_intersect(a, b, $output);
                solve satisfy;
                """.trimIndent(),
            )

            val result = BacktrackSolver(program.problem.bake()).solve()

            assertIs<SolveResult.Unsat>(result, "result $output")
        }
    }
}
