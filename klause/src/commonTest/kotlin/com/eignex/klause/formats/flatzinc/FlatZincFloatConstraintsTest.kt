package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFloatConstraintsTest {

    private fun solve(src: String, buckets: Int = 5): SolveResult =
        BacktrackSolver(parseFlatZinc(src, floatBuckets = buckets).problem.bake())
            .solve(BacktrackParams(randomSeed = 0L))

    @Test
    fun `purely linear float model should lower to continuous columns`() {
        val program = parseFlatZinc(
            """
            var 0.0..10.0: x;
            constraint float_lin_le([2.0], [x], 6.0);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )
        assertEquals(1, program.problem.numRealVars)
        assertEquals(0, program.problem.numIntVars)
        val r = assertIs<SolveResult.Sat>(
            BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L)),
        )
        val x = r.assignment.reals[0]
        assertTrue(2.0 * x <= 6.0 + 1e-6 && x in 0.0..10.0, "x=$x")
    }

    @Test
    fun `default float lowering should bucket linear and nonlinear floats`() {
        val program = parseFlatZinc(
            """
            var 0.0..10.0: x;
            var -5.0..5.0: z;
            var 0.0..5.0: w;
            constraint float_lin_le([1.0], [x], 5.0);
            constraint float_abs(z, w);
            solve satisfy;
            """.trimIndent(),
        )
        assertEquals(0, program.problem.numRealVars)
        assertEquals(3, program.problem.numIntVars)
    }

    @Test
    fun `nonlinear float constraint should keep the whole model bucketed`() {
        val program = parseFlatZinc(
            """
            var 0.0..10.0: x;
            var 0.0..10.0: y;
            constraint float_lin_le([1.0], [x], 5.0);
            constraint float_abs(x, y);
            solve satisfy;
            """.trimIndent(),
        )
        assertEquals(0, program.problem.numRealVars)
        assertTrue(program.problem.numIntVars >= 2)
    }

    @Test
    fun `false float constant comparison should be unsat`() {
        val src = """
            constraint float_le(5.0, 3.0);
            solve satisfy;
        """.trimIndent()
        assertIs<SolveResult.Unsat>(solve(src))
    }

    @Test
    fun `float powers should reject variable negative and fractional exponents`() {
        for (exponent in listOf("y", "-1.0", "0.5")) {
            assertFailsWith<UnsupportedFlatZincException> {
                parseFlatZinc(
                    "var -3.0..3.0: x; var 0.0..2.0: y; " +
                        "constraint float_pow(x, $exponent, 4.0); solve satisfy;",
                    exactFloats = true,
                )
            }
        }
    }
}
