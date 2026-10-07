package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFloatPolicyTest {
    @Test
    fun `default products preserve finite choices and intermediate values`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            var float: y;
            var float: rate;
            var float: intermediate;
            var float: z :: output_var;
            array[1..1] of var float: values = [x];
            constraint float_times(intermediate, rate, z);
            constraint float_times(values[1], y, intermediate);
            constraint array_float_element(i, [0.125], x);
            constraint array_float_element(i, [0.375], y);
            constraint array_float_element(i, [0.5], rate);
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 2,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("z = 0.0234375;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `a nonlinear continuation falls back without rounding unrelated choices`() {
        val program = parseFlatZinc(
            """
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            var 0.0..1.0: intermediate;
            var 0.0..1.0: z;
            var 0.0..1.0: independent;
            var 0.5..0.5: multiplier;
            var 0.0..1.0: answer :: output_var;
            var 0.0..1.0: alias = x;
            var 1..1: i;
            constraint array_float_element(i, [0.125], independent);
            constraint array_float_element(i, [0.375], alias);
            constraint float_times(x, y, intermediate);
            constraint float_times(intermediate, y, z);
            constraint float_times(independent, multiplier, answer);
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 2,
        )

        for (name in listOf("x", "alias", "y", "intermediate", "z")) {
            assertFalse(program.floatVarsByName.getValue(name).lpOnly)
        }
        assertTrue(program.floatVarsByName.getValue("independent").lpOnly)

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("answer = 0.0625;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `finite expansion counts repeated entries and product alternatives`() {
        val cases = listOf("0.125, 0.125, 0.375" to true, "0.125, 0.125, 0.125, 0.375" to false)
        for ((entries, exactExpected) in cases) {
            val model = FlatZincParser(
                FlatZincLexer(
                    """
                    var 1..1: i;
                    var 0.0..1.0: x;
                    var 0.0..1.0: y;
                    var 0.0..1.0: z;
                    constraint array_float_element(i, [$entries], x);
                    constraint float_times(x, y, z);
                    solve satisfy;
                    """.trimIndent(),
                ),
            ).parse()

            val program = FlatZincCompiler(model, floatBuckets = 2, floatChoiceLimit = 5).compile()

            for (name in listOf("x", "y", "z")) {
                assertTrue(program.floatVarsByName.getValue(name).lpOnly == exactExpected)
            }
        }
    }

    @Test
    fun `exact mode declines an oversized finite encoding`() {
        for (result in listOf("x", "0.125")) {
            val model = FlatZincParser(
                FlatZincLexer(
                    """
                    var 1..1: i;
                    var float: x;
                    constraint array_float_element(i, [0.125, 0.125, 0.375], $result);
                    solve satisfy;
                    """.trimIndent(),
                ),
            ).parse()

            val error = assertFailsWith<UnsupportedFlatZincException> {
                FlatZincCompiler(model, exactFloats = true, floatChoiceLimit = 2).compile()
            }

            assertTrue(error.message.orEmpty().contains("2 alternative limit"))
            assertTrue(error.message.orEmpty().contains("(at 3:1)"))
        }
    }

    @Test
    fun `finite open floats retain choices outside the grid clamp`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x :: output_var;
            constraint array_float_element(i, [2000000.0], x);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("x = 2000000.0;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `continuous components without finite choices use the grid`() {
        val program = parseFlatZinc("var 0.0..1.0: x; constraint float_le(x, 0.5); solve satisfy;", floatBuckets = 2)

        assertFalse(program.floatVarsByName.getValue("x").lpOnly)
    }

    @Test
    fun `unconstrained non-objective floats do not add grid search variables`() {
        val program = parseFlatZinc(
            """
            var float: unused;
            array[1..2] of var float: values;
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 1024,
        )

        assertEquals(0, program.problem.numIntVars)
        assertEquals(3, program.problem.numRealVars)
        assertTrue(program.floatVarsByName.values.all { it.lpOnly })
    }

    @Test
    fun `an unconstrained float objective keeps the default grid policy`() {
        for (goal in listOf("minimize", "maximize")) {
            val program = parseFlatZinc("var 0.0..1.0: x; solve $goal x;", floatBuckets = 2)

            assertFalse(program.floatVarsByName.getValue("x").lpOnly)
            assertEquals(1, program.problem.numIntVars)
        }
    }

    @Test
    fun `finite float lowering rejects constant division by zero`() {
        for (exact in listOf(false, true)) {
            val error = assertFailsWith<FlatZincParseException> {
                parseFlatZinc(
                    """
                    var 1..1: i;
                    var float: x;
                    var float: y;
                    constraint array_float_element(i, [0.0], x);
                    constraint float_div(x, 0.0, y);
                    solve satisfy;
                    """.trimIndent(),
                    exactFloats = exact,
                )
            }

            assertTrue(error.message.orEmpty().contains("division by zero"))
        }
    }
}
