package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincIntegerFloatImagesTest {
    // Binary64 `0.01 * 200` exceeds the declared bound `2.0` by `3/72057594037927936`.
    private val roundedBound = """
        var {50, 100, 200}: n;
        var 50.0..200.0: raw;
        var 0.5..2.0: scaled;
        constraint int2float(n, raw);
        constraint float_lin_eq([0.01, -1.0], [raw, scaled], 0.0);
        solve satisfy;
    """.trimIndent()

    @Test
    fun `a rounded bound within the declared slack should keep the original representation`() {
        val program = parseFlatZinc(roundedBound, floatBuckets = 2)

        assertTrue(program.floatVarsByName.getValue("scaled").lpOnly)
        assertFalse(program.floatRepresentations.getValue("scaled").approximate)
    }

    @Test
    fun `rounded derived bounds should preserve exact source intake and a private grid view`() {
        val program = parseFlatZinc(roundedBound, floatBuckets = 2,
            semanticOverrides = SemanticOverrides(feasibilityTolerance = SourceTolerance()))

        assertFalse(program.floatVarsByName.getValue("scaled").lpOnly)
        val exact = parseFlatZinc(roundedBound, exactFloats = true).sourceProgram
        assertTrue(exact.floatVarsByName.values.all { it.lpOnly })
        assertTrue(exact.problem.realLower.contentEquals(doubleArrayOf(50.0, 0.5)))
        assertTrue(exact.problem.realUpper.contentEquals(doubleArrayOf(200.0, 2.0)))
        assertTrue(program.floatRepresentations.getValue("scaled").approximate)
        val reason = program.floatRepresentations.getValue("scaled").approximation.orEmpty()
        assertTrue(reason.contains("rounded arithmetic"))
    }

    @Test
    fun `scaled integer products should retain values through array aliases`() {
        for (exact in listOf(false, true)) {
            val program = parseFlatZinc(
                """
                var 3..3: n;
                var 2..2: m;
                var float: raw_x;
                var float: raw_y;
                var 0.0..1.0: x;
                var 0.0..1.0: y;
                var 0.0..1.0: z :: output_var;
                array[1..1] of var float: values = [x];
                constraint float_times(y, values[1], z);
                constraint float_lin_eq([0.125, -1.0], [raw_x, x], 0.0);
                constraint float_lin_eq([1.0, -0.25], [y, raw_y], 0.0);
                constraint int2float(n, raw_x);
                constraint int2float(m, raw_y);
                solve satisfy;
                """.trimIndent(),
                floatBuckets = 2,
                exactFloats = exact,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result).assignment
            assertTrue("z = 0.1875;" in writeFlatZincSolution(program, assignment))
        }
    }

    @Test
    fun `scaled products should not round the integer times the source scale`() {
        val program = parseFlatZinc(
            """
            var 3..3: n;
            var float: raw;
            var float: scaled;
            var 0.0..1.0: y;
            var float: z;
            constraint float_times(scaled, y, z);
            constraint float_lin_eq([0.1, -1.0], [raw, scaled], 0.0);
            constraint int2float(n, raw);
            constraint float_eq(y, 0.5);
            constraint float_eq(z, 0.15);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Unsat>(result, "LP evidence: ${result.stats.lp}")
    }
}
