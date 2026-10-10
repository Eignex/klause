package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincCompilerFloatTest {

    @Test
    fun `float arrays preserve references and constant values`() {
        val initializers = listOf("[x, 72.0]", "[x, c]", "[x, constants[1]]", "[source[1], 72.0]")
        for ((initializer, exact) in initializers.flatMap { listOf(it to false, it to true) }) {
            val program = parseFlatZinc(
                """
                float: c = 72.0;
                array[1..1] of float: constants = [72.0];
                var 0.0..4.0: x;
                array[1..1] of var float: source = [x];
                array[1..2] of var float: values :: output_array([1..2]) = $initializer;
                constraint float_lin_eq([1.0, 1.0], values, 74.0);
                solve satisfy;
                """.trimIndent(),
                floatBuckets = 5,
                exactFloats = exact,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result, initializer).assignment
            val output = writeFlatZincSolution(program, assignment)
            assertTrue("values = [2.0, 72.0];" in output, "$initializer: $output")
        }
    }

    @Test
    fun `open scalar and array floats use the configured search range`() {
        for (declaration in listOf("var float: x;", "array[1..2] of var float: x;")) {
            val program = parseFlatZinc(
                "$declaration\n" + if (declaration.startsWith("array")) {
                    "constraint float_le(x[1], 0.0); constraint float_le(x[2], 0.0); solve satisfy;"
                } else {
                    "constraint float_le(x, 0.0); solve satisfy;"
                },
                unboundedFloatLo = -3.0,
                unboundedFloatHi = 7.0,
                floatBuckets = 5,
            )

            for (float in program.floatVarsByName.values) {
                assertEquals(-3.0, float.valueOf(0))
                assertEquals(7.0, float.valueOf(4))
            }
            assertEquals(0, program.problem.numRealVars)
        }
    }

    @Test
    fun `exact float declarations retain open bounds`() {
        for (declaration in listOf("var float: x;", "array[1..2] of var float: x;")) {
            val program = parseFlatZinc("$declaration\nsolve satisfy;", exactFloats = true)

            assertEquals(0, program.problem.numIntVars)
            assertTrue(program.problem.realLower.all { it == Double.NEGATIVE_INFINITY })
            assertTrue(program.problem.realUpper.all { it == Double.POSITIVE_INFINITY })
        }
    }

    @Test
    fun `exact float lowering declines nonlinear constraints without bucketing`() {
        val error = assertFailsWith<UnsupportedFlatZincException> {
            parseFlatZinc(
                "var -1.0..1.0: x;\nconstraint float_times(x, x, x);\nsolve satisfy;",
                exactFloats = true,
            )
        }

        assertTrue("unsupported by exact float lowering" in error.message.orEmpty())
    }

    @Test
    fun `unrepresentable scaled float arithmetic is declined with an exact alternative`() {
        val error = assertFailsWith<UnsupportedFlatZincException> {
            parseFlatZinc(
                "var 0.0..1.0: x;\nconstraint float_lin_le([1e20], [x], 1.0);\nsolve satisfy;",
            )
        }

        assertTrue("use --exact" in error.message.orEmpty())
    }
}
