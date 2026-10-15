package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFloatSelectionTest {
    @Test
    fun `exact float selection should retain an affine integer image`() {
        val program =
            parseFlatZinc(
                """
            var 0..3: n;
            var 2..2: i;
            var float: raw;
            var float: scaled;
            constraint int2float(n, raw);
            constraint float_lin_eq([0.125, -1.0], [raw, scaled], 0.0);
            constraint array_float_element(i, [0.125, 0.375], scaled);
            solve satisfy;
                """.trimIndent(),
                exactFloats = true,
            )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0))

        assertEquals(3L, assertIs<SolveResult.Sat>(result).assignment.intValue(program.intVarsByName.getValue("n")))
    }

    @Test
    fun `exact float selection should retain its binary64 source entry`() {
        val program =
            parseFlatZinc(
                """
            var 2..2: i;
            var 0.0..1.0: x;
            constraint array_float_element(i, [0.125, 0.375, 0.125], x);
            solve satisfy;
                """.trimIndent(),
                exactFloats = true,
            )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0))

        val value = program.floatVarsByName.getValue("x")
        assertTrue(value.lpOnly)
        assertEquals(0.375, assertIs<SolveResult.Sat>(result).assignment.reals[value.varId])
        assertEquals(1, program.problem.factors.filterIsInstance<RealElement>().size)
    }

    @Test
    fun `grid selection should preserve repeated entries over a wide source index`() {
        val model = FlatZincParser(
            FlatZincLexer(
                """
            var 0..4294967297: i;
            var 0.0..1.0: x;
            constraint array_float_element(i, [0.0, 0.5, 0.5, 1.0], x);
            solve satisfy;
                """.trimIndent(),
            ),
        ).parse()
        val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()
        val value = program.floatVarsByName.getValue("x")
        val index = program.intVarsByName.getValue("i")
        val solver = BacktrackSolver(program.problem.bake())

        for ((selected, expected) in listOf(1L to 0.0, 2L to 0.5, 3L to 0.5, 4L to 1.0)) {
            val result = solver.solve(
                BacktrackParams(
                    randomSeed = 0,
                    assumptions = Assumptions(ints = mapOf(index to selected)),
                ),
            )

            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertEquals(expected, value.valueOf(sample.intValue(value.varId).toInt()))
        }
        assertFalse(value.lpOnly)
        assertEquals(1, program.problem.factors.filterIsInstance<Element>().size)
    }

    @Test
    fun `grid selection should exclude source indexes outside the array`() {
        val model = FlatZincParser(
            FlatZincLexer(
                """
            var 0..3: i;
            var 0.0..1.0: x;
            constraint array_float_element(i, [0.0, 1.0], x);
            solve satisfy;
                """.trimIndent(),
            ),
        ).parse()
        val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()
        val index = program.intVarsByName.getValue("i")
        val solver = BacktrackSolver(program.problem.bake())

        for (selected in listOf(0L, 3L)) {
            val result = solver.solve(
                BacktrackParams(
                    randomSeed = 0,
                    assumptions = Assumptions(ints = mapOf(index to selected)),
                ),
            )

            assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `grid extrema should retain the result coordinate units`() {
        for ((operation, selected, expected) in listOf(Triple("float_min", 0, -1.0), Triple("float_max", 2, 2.0))) {
            val model = FlatZincParser(
                FlatZincLexer(
                    """
                var 1..1: i;
                var -1.0..1.0: x;
                var 0.0..2.0: y;
                var -1.0..2.0: r;
                constraint array_float_element(i, [-1.0, 1.0], x);
                constraint float_eq(y, $selected.0);
                constraint $operation(x, y, r);
                solve satisfy;
                    """.trimIndent(),
                ),
            ).parse()
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
    fun `grid extrema should reject values absent from the scaled result grid`() {
        val model = FlatZincParser(
            FlatZincLexer(
                """
            var 2..2: i;
            var -1.0..1.0: x;
            var 0.0..2.0: y;
            var -1.0..2.0: r;
            constraint array_float_element(i, [-1.0, 0.0, 1.0], x);
            constraint float_eq(y, 0.0);
            constraint float_min(x, y, r);
            solve satisfy;
                """.trimIndent(),
            ),
        ).parse()
        val program = FlatZincCompiler(model, floatBuckets = 3, floatChoiceLimit = 1).compile()

        val result = BacktrackSolver(program.problem.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `exact float extrema should retain rational source operands`() {
        for ((operation, expected) in listOf("float_min" to "1/3", "float_max" to "2/3")) {
            val program =
                parseFlatZinc(
                    """
                var 0.0..1.0: x;
                var 0.0..1.0: y;
                var 0.0..1.0: r;
                constraint float_lin_eq([3.0], [x], 1.0);
                constraint float_lin_eq([3.0], [y], 2.0);
                constraint $operation(x, y, r);
                solve satisfy;
                    """.trimIndent(),
                    exactFloats = true,
                )
            val relation = program.problem.factors.filterIsInstance<RealArrayMinMax>().single()

            val result = BacktrackSolver.source(program.problem).solve()

            assertEquals(expected, assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(relation.result))
        }
    }

    @Test
    fun `exact float extrema should preserve nested constants and result aliases`() {
        val program =
            parseFlatZinc(
                """
            var -1.0..2.0: x;
            var 0.0..2.0: r;
            constraint float_min(x, 1.5, r);
            constraint float_max(r, 0.5, r);
            constraint float_eq(x, 1.25);
            solve satisfy;
                """.trimIndent(),
                exactFloats = true,
            )

        val result = BacktrackSolver.source(program.problem).solve()

        assertEquals(2, program.problem.factors.filterIsInstance<RealArrayMinMax>().size)
        val output = program.floatVarsByName.getValue("r")
        assertEquals("5/4", assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(output.varId))
    }
}
