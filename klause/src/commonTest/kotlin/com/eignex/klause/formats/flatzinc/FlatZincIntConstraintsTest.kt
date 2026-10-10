package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals

class FlatZincIntConstraintsTest {

    @Test
    fun `zero sum expansion falls back for every row when the model exceeds its clause limit`() {
        for (limit in listOf(5, 6)) {
            for (reverse in listOf(false, true)) {
                val rows = listOf(
                    "constraint int_lin_le_reif([2, 3], [x, y], 0, r);",
                    "constraint int_lin_eq_reif([3, 2], [y, x], 0, s);",
                ).let { if (reverse) it.reversed() else it }
                val model = FlatZincParser(
                    FlatZincLexer(
                        """
                        var bool: a;
                        var bool: b;
                        var bool: r;
                        var bool: s;
                        var 0..1: x;
                        var 0..1: y;
                        ${rows.joinToString("\n")}
                        constraint bool2int(a, x);
                        constraint bool2int(b, y);
                        solve satisfy;
                        """.trimIndent(),
                    ),
                ).parse()

                val program = FlatZincCompiler(model, booleanZeroSumClauseLimit = limit).compile()

                assertEquals(if (limit == 5) 4 else 2, program.problem.factors.count { it is ReifiedLinear })
            }
        }
    }

    @Test
    fun `reified nonnegative zero sums preserve every Boolean assignment`() {
        for (op in listOf("le", "eq")) {
            val program = parseFlatZinc(
                """
                var bool: a;
                var bool: b;
                var bool: r;
                var 0..1: x;
                var 0..1: y;
                constraint int_lin_${op}_reif([2, 3, 0], [x, x, y], 0, r);
                constraint bool2int(a, x);
                constraint bool2int(b, y);
                solve satisfy;
                """.trimIndent(),
            )

            val assignments = BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
                .map { sample -> listOf("a", "b", "r").map { sample.bools[program.boolVarsByName.getValue(it)] } }
                .toSet()

            assertEquals(
                setOf(
                    listOf(false, false, true), listOf(false, true, true),
                    listOf(true, false, false), listOf(true, true, false),
                ),
                assignments,
            )
        }
    }

    @Test
    fun `signed sums preserve cancellation between Boolean channels`() {
        val program = parseFlatZinc(
            """
            var bool: a;
            var bool: b;
            var 0..1: x;
            var 0..1: y;
            constraint bool2int(a, x);
            constraint bool2int(b, y);
            constraint int_lin_eq([1, -1], [x, y], 0);
            solve satisfy;
            """.trimIndent(),
        )

        val assignments = BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { sample -> listOf("a", "b").map { sample.bools[program.boolVarsByName.getValue(it)] } }
            .toSet()

        assertEquals(setOf(listOf(false, false), listOf(true, true)), assignments)
    }

    @Test
    fun `unreified zero sums pin only nonzero weighted channels`() {
        val program = parseFlatZinc(
            """
            var bool: a;
            var bool: b;
            var 0..1: x;
            var 0..1: y;
            constraint int_lin_le([3, 0], [x, y], 0);
            constraint bool2int(a, x);
            constraint bool2int(b, y);
            solve satisfy;
            """.trimIndent(),
        )

        val assignments = BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { sample -> listOf("a", "b").map { sample.bools[program.boolVarsByName.getValue(it)] } }
            .toSet()

        assertEquals(setOf(listOf(false, false), listOf(false, true)), assignments)
    }
}
