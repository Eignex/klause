package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.writeFlatZincSolution
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincIntegerFloatImagesTest {
    @Test
    fun `rounded derived bounds use the grid and decline exact mode`() {
        val source = """
            var {50, 100, 200}: n;
            var 50.0..200.0: raw;
            var 0.5..2.0: scaled;
            constraint int2float(n, raw);
            constraint float_lin_eq([0.01, -1.0], [raw, scaled], 0.0);
            solve satisfy;
        """.trimIndent()

        val program = parseFlatZinc(source, floatBuckets = 2)

        assertFalse(program.floatVarsByName.getValue("scaled").lpOnly)
        val error = assertFailsWith<UnsupportedFlatZincException> { parseFlatZinc(source, exactFloats = true) }
        assertTrue(error.message.orEmpty().contains("require rounded arithmetic"))
    }

    @Test
    fun `scaled integer products retain values through array aliases`() {
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
    fun `scaled products do not round the integer times the source scale`() {
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

        assertIs<SolveResult.Unsat>(result)
    }
}
