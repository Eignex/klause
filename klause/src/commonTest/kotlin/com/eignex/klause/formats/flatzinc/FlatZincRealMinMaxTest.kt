package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlatZincRealMinMaxTest {
    @Test
    fun `exact float extrema retain rational source operands`() {
        for ((operation, expected) in listOf("float_min" to "1/3", "float_max" to "2/3")) {
            val program = parseFlatZinc("""
                var 0.0..1.0: x;
                var 0.0..1.0: y;
                var 0.0..1.0: r;
                constraint float_lin_eq([3.0], [x], 1.0);
                constraint float_lin_eq([3.0], [y], 2.0);
                constraint $operation(x, y, r);
                solve satisfy;
            """.trimIndent(), exactFloats = true)
            val relation = program.problem.factors.filterIsInstance<RealArrayMinMax>().single()

            val result = BacktrackSolver.source(program.problem).solve()

            assertEquals(expected, assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(relation.result))
        }
    }

    @Test
    fun `exact float extrema preserve nested constants and result aliases`() {
        val program = parseFlatZinc("""
            var -1.0..2.0: x;
            var 0.0..2.0: r;
            constraint float_min(x, 1.5, r);
            constraint float_max(r, 0.5, r);
            constraint float_eq(x, 1.25);
            solve satisfy;
        """.trimIndent(), exactFloats = true)

        val result = BacktrackSolver.source(program.problem).solve()

        assertEquals(2, program.problem.factors.filterIsInstance<RealArrayMinMax>().size)
        val output = program.floatVarsByName.getValue("r")
        assertEquals("5/4", assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(output.varId))
    }
}
