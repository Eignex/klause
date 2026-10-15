package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFloatSelectionTest {
    @Test
    fun `exact float selection retains an affine integer image`() {
        val program = parseFlatZinc("""
            var 0..3: n;
            var 2..2: i;
            var float: raw;
            var float: scaled;
            constraint int2float(n, raw);
            constraint float_lin_eq([0.125, -1.0], [raw, scaled], 0.0);
            constraint array_float_element(i, [0.125, 0.375], scaled);
            solve satisfy;
        """.trimIndent(), exactFloats = true)

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(3L, assertIs<SolveResult.Sat>(result).assignment.intValue(program.intVarsByName.getValue("n")))
    }

    @Test
    fun `exact float selection retains its binary64 source entry`() {
        val program = parseFlatZinc("""
            var 2..2: i;
            var 0.0..1.0: x;
            constraint array_float_element(i, [0.125, 0.375, 0.125], x);
            solve satisfy;
        """.trimIndent(), exactFloats = true)

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0))

        val value = program.floatVarsByName.getValue("x")
        assertTrue(value.lpOnly)
        assertEquals(0.375, assertIs<SolveResult.Sat>(result).assignment.reals[value.varId])
        assertEquals(1, program.problem.factors.filterIsInstance<RealElement>().size)
    }

    @Test
    fun `grid selection preserves repeated entries over a wide source index`() {
        val model = FlatZincParser(FlatZincLexer("""
            var 0..4294967297: i;
            var 0.0..1.0: x;
            constraint array_float_element(i, [0.0, 0.5, 0.5, 1.0], x);
            solve satisfy;
        """.trimIndent())).parse()
        val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()
        val value = program.floatVarsByName.getValue("x")
        val index = program.intVarsByName.getValue("i")
        val solver = BacktrackSolver(program.problem.bake())

        for ((selected, expected) in listOf(1L to 0.0, 2L to 0.5, 3L to 0.5, 4L to 1.0)) {
            val result = solver.solve(BacktrackParams(randomSeed = 0,
                assumptions = Assumptions(ints = mapOf(index to selected))))

            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertEquals(expected, value.valueOf(sample.intValue(value.varId).toInt()))
        }
        assertFalse(value.lpOnly)
        assertEquals(1, program.problem.factors.filterIsInstance<Element>().size)
    }

    @Test
    fun `grid selection excludes source indexes outside the array`() {
        val model = FlatZincParser(FlatZincLexer("""
            var 0..3: i;
            var 0.0..1.0: x;
            constraint array_float_element(i, [0.0, 1.0], x);
            solve satisfy;
        """.trimIndent())).parse()
        val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()
        val index = program.intVarsByName.getValue("i")
        val solver = BacktrackSolver(program.problem.bake())

        for (selected in listOf(0L, 3L)) {
            val result = solver.solve(BacktrackParams(randomSeed = 0,
                assumptions = Assumptions(ints = mapOf(index to selected))))

            assertIs<SolveResult.Unsat>(result)
        }
    }
}
