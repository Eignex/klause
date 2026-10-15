package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlatZincSelectionTest {
    @Test
    fun `binary extrema retain native operands with result aliases`() {
        for (name in listOf("int_min", "int_max")) {
            val program = parseFlatZinc("""
                var 0..2: x;
                var 1..1: y;
                constraint $name(x, y, x);
                solve satisfy;
            """.trimIndent())
            val factor = program.problem.factors.filterIsInstance<ArrayMinMax>().single()

            assertEquals(listOf(0, 1), factor.xs.toList())
            assertEquals(0, factor.result)
            val sample = assertIs<SolveResult.Sat>(BacktrackSolver(program.problem.bake())
                .solve(BacktrackParams(randomSeed = 0))).assignment
            assertEquals(sample.ints[0], if (name == "int_max") maxOf(sample.ints[0], 1L)
                else minOf(sample.ints[0], 1L))
        }
    }

    @Test
    fun `array extrema and selection retain correlated native factors`() {
        val program = parseFlatZinc("""
            var 1..2: i;
            var 0..2: x;
            var 0..2: y;
            var 0..2: m;
            constraint array_int_maximum(m, [x, y, x]);
            constraint array_var_int_element(i, [x, y], m);
            constraint int_eq(x, 0);
            constraint int_eq(y, 2);
            solve satisfy;
        """.trimIndent())

        assertEquals(1, program.problem.factors.filterIsInstance<ArrayMinMax>().size)
        assertEquals(1, program.problem.factors.filterIsInstance<Element>().single().indexOffset)
        val sample = assertIs<SolveResult.Sat>(BacktrackSolver(program.problem.bake())
            .solve(BacktrackParams(randomSeed = 0))).assignment
        assertEquals(2L, sample.ints[0])
        assertEquals(2L, sample.ints[3])
    }
}
