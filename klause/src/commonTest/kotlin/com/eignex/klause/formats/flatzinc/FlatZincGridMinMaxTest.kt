package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FlatZincGridMinMaxTest {
    @Test
    fun `grid extrema retain the result coordinate units`() {
        for ((operation, selected, expected) in listOf(Triple("float_min", 0, -1.0), Triple("float_max", 2, 2.0))) {
            val model = FlatZincParser(FlatZincLexer("""
                var 1..1: i;
                var -1.0..1.0: x;
                var 0.0..2.0: y;
                var -1.0..2.0: r;
                constraint array_float_element(i, [-1.0, 1.0], x);
                constraint float_eq(y, $selected.0);
                constraint $operation(x, y, r);
                solve satisfy;
            """.trimIndent())).parse()
            val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()

            val result = BacktrackSolver(program.problem.bake()).solve()

            val value = program.floatVarsByName.getValue("r")
            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertFalse(value.lpOnly)
            assertEquals(1, program.problem.factors.filterIsInstance<GridArrayMinMax>().size)
            assertEquals(expected, value.valueOf(sample.intValue(value.varId).toInt()))
        }
    }

    @Test
    fun `grid extrema reject values absent from the scaled result grid`() {
        val model = FlatZincParser(FlatZincLexer("""
            var 2..2: i;
            var -1.0..1.0: x;
            var 0.0..2.0: y;
            var -1.0..2.0: r;
            constraint array_float_element(i, [-1.0, 0.0, 1.0], x);
            constraint float_eq(y, 0.0);
            constraint float_min(x, y, r);
            solve satisfy;
        """.trimIndent())).parse()
        val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()

        val result = BacktrackSolver(program.problem.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }
}
