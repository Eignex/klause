package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFiniteFloatProductsTest {
    @Test
    fun `products preserve selected float values through array aliases in either operand`() {
        for (operands in listOf("values[1], y", "y, values[1]")) {
            val program = parseFlatZinc(
                """
                var 1..2: i = 2;
                var 0.0..1.0: x;
                array[1..1] of var float: values = [x];
                var 0.0..1.0: y;
                var 0.0..1.0: z :: output_var;
                constraint float_times($operands, z);
                constraint array_float_element(i, [0.125, 0.375], x);
                constraint float_eq(y, 0.5);
                solve satisfy;
                """.trimIndent(),
                floatBuckets = 2,
                exactFloats = true,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result).assignment
            assertTrue("z = 0.1875;" in writeFlatZincSolution(program, assignment))
        }
    }

    @Test
    fun `a product chain retains exact intermediate values`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            var float: y;
            var float: rate;
            var float: intermediate;
            var float: z :: output_var;
            constraint float_times(intermediate, rate, z);
            constraint float_times(x, y, intermediate);
            constraint array_float_element(i, [0.125], x);
            constraint array_float_element(i, [0.375], y);
            constraint array_float_element(i, [0.5], rate);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("z = 0.0234375;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `a rounded product does not satisfy an exact equality`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            var 0.1..0.1: y;
            constraint array_float_element(i, [0.1], x);
            constraint float_times(x, y, 0.010000000000000002);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `a singleton float domain supports a constant product result`() {
        for (exact in listOf(false, true)) {
            val program = parseFlatZinc(
                """
                var 2.0..2.0: x;
                var float: y :: output_var;
                constraint float_times(x, y, 6.0);
                solve satisfy;
                """.trimIndent(),
                exactFloats = exact,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result).assignment
            assertTrue("y = 3.0;" in writeFlatZincSolution(program, assignment))
        }
    }
}
