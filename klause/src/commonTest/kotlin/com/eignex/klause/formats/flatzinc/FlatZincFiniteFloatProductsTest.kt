package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFiniteFloatProductsTest {
    @Test
    fun `selecting a finite float value excludes the other choices during propagation`() {
        for (count in listOf(3, 6)) {
            val entries = (1..count).joinToString(", ") { "$it.0" }
            val model = FlatZincParser(
                FlatZincLexer(
                    "var 1..1: i; var float: x; constraint array_float_element(i, [$entries], x); solve satisfy;",
                ),
            ).parse()
            val compiler = FlatZincCompiler(model)
            val program = compiler.compile()

            val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)
            for ((key, literal) in compiler.floatValueLiterals) {
                val pinned = deductions.boolValueOrNull(Lit.variable(literal))
                assertEquals(key.second == 1.0, pinned?.let { Lit.evaluate(literal, it) })
            }
        }
    }

    @Test
    fun `signed zeros share a finite choice`() {
        val program = parseFlatZinc(
            """
            var 1..2: i;
            var float: x;
            constraint array_float_element(i, [0.0, -0.0], x);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `disjoint constant arrays have no common finite float choice`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            constraint array_float_element(i, [1.0], x);
            constraint array_float_element(i, [2.0], x);
            solve satisfy;
            """.trimIndent(),
        )

        assertIs<PropagationResult.Unsat>(program.problem.baked)
    }

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
