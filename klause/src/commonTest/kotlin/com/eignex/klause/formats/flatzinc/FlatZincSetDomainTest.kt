package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlatZincSetDomainTest {

    @Test
    fun `a set universe allocates one indicator per distinct value`() {
        val program = parseFlatZinc(
            """
            var set of {1, 1, 2}: s;
            solve satisfy;
            """.trimIndent(),
        )

        assertEquals(2, program.problem.numBoolVars)
        assertEquals(intArrayOf(1, 2).toList(), program.setVarsByName.getValue("s").elements.toList())
    }

    @Test
    fun `set membership rejects an integer outside the signed integer universe`() {
        val program = parseFlatZinc(
            """
            var 4294967296..4294967296: x;
            var set of {0}: s = {0};
            constraint set_in(x, s);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `wide integer constants do not alias set universe elements`() {
        for (set in listOf("s", "{0}")) {
            val program = parseFlatZinc(
                """
                var set of {0}: s = {0};
                constraint set_in_reif(4294967296, $set, false);
                solve satisfy;
                """.trimIndent(),
            )

            val result = BacktrackSolver(program.problem.bake()).solve()

            assertIs<SolveResult.Sat>(result)
        }
    }

    @Test
    fun `reified membership preserves values when the integer domain crosses the signed integer boundary`() {
        val program = parseFlatZinc(
            """
            var -2147483649..-2147483647: x;
            var set of {-2147483648}: s = {-2147483648};
            constraint int_eq(x, -2147483648);
            constraint set_in_reif(x, s, true);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve()

        assertIs<SolveResult.Sat>(result)
    }
}
