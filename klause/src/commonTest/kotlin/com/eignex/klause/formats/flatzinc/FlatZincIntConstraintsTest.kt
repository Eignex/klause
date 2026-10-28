package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class FlatZincIntConstraintsTest {
    @Test
    fun `cumulative subwindows should remove a machine value through its bool2int channel`() {
        val program = parseFlatZinc(
            """
            var 0..100: spectator;
            var 100..102: a;
            var 100..102: b;
            var 100..102: c;
            var 1..3: machine;
            var bool: selected;
            var 0..1: q;
            constraint int_eq_reif(machine, 2, selected);
            constraint bool2int(selected, q);
            constraint cumulative([spectator,a,b,c], [1,2,2,2], [1,1,1,q], 1);
            solve satisfy;
            """.trimIndent(),
        )

        val session = PropagationSession(program.problem)

        assertEquals(IntDomain(0, 0), session.intDomain(program.intVarsByName.getValue("q")))
        val domain = session.intDomain(program.intVarsByName.getValue("machine"))
        assertEquals(listOf(1L, 3L), (domain.min..domain.max).filter { it in domain })
    }

    @Test
    fun `equal pipe lengths should retain independent scaled cost slack`() {
        val program = parseFlatZinc(
            """
            var 12811..12811: length_a;
            var 12811..12811: length_b;
            var 0..1000000000: scaled_a;
            var 0..1000000000: scaled_b;
            var 910..910: cost_a;
            var 1050..1050: cost_b;
            constraint int_lin_le([1,-1000], [length_a,scaled_a], 0);
            constraint int_lin_eq([1,-70], [cost_a,scaled_a], 0);
            constraint int_lin_le([1,-1000], [length_b,scaled_b], 0);
            constraint int_lin_eq([1,-70], [cost_b,scaled_b], 0);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `wide all different predicates should retain their global relation`() {
        for (predicate in listOf("all_different_int", "alldifferent_except_0")) {
            val program = parseFlatZinc(
                """
                var 0..3000000000: x;
                var 0..3000000000: y;
                var 0..3000000000: z;
                constraint $predicate([x, y, z]);
                solve satisfy;
                """.trimIndent(),
            )

            val relation = assertIs<AllDifferent>(program.problem.factors.single())
            assertEquals(3, relation.vars.size)
            assertEquals(if (predicate == "all_different_int") emptyList() else listOf(0L), relation.exceptSet.toList())
        }
    }

    @Test
    fun `negative bounds should make nonnegative channel sums false without restricting their inputs`() {
        for (op in listOf("le", "eq")) {
            for (bound in listOf(-1L, Long.MIN_VALUE)) {
                val program = parseFlatZinc(
                    """
                    var bool: a;
                    var bool: b;
                    var bool: r;
                    var 0..1: x;
                    var 0..1: y;
                    constraint int_lin_${op}_reif([${Long.MAX_VALUE}, 2, 0], [x, x, y], $bound, r);
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
                        listOf(false, false, false),
                        listOf(false, true, false),
                        listOf(true, false, false),
                        listOf(true, true, false),
                    ),
                    assignments,
                )
            }
        }
    }

    @Test
    fun `hard nonnegative channel sums with negative bounds should be infeasible`() {
        for (op in listOf("le", "eq")) {
            val program = parseFlatZinc(
                """
                var bool: a;
                var 0..1: x;
                constraint int_lin_$op([1], [x], -1);
                constraint bool2int(a, x);
                solve satisfy;
                """.trimIndent(),
            )

            val assignments = BacktrackSolver(program.problem.bake()).enumerate().toList()

            assertEquals(emptyList(), assignments)
        }
    }

    @Test
    fun `zero sum expansion should fall back for every row when the model exceeds its clause limit`() {
        for (limit in listOf(6, 7)) {
            for (reverse in listOf(false, true)) {
                val rows = listOf(
                    "constraint int_lin_le_reif([2, 3], [x, y], 0, r);",
                    "constraint int_lin_eq_reif([3, 2], [y, x], 0, s);",
                    "constraint int_lin_le_reif([2, 3], [x, y], -1, t);",
                ).let { if (reverse) it.reversed() else it }
                val model = FlatZincParser(
                    FlatZincLexer(
                        """
                        var bool: a;
                        var bool: b;
                        var bool: r;
                        var bool: s;
                        var bool: t;
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

                assertEquals(if (limit == 6) 5 else 2, program.problem.factors.count { it is ReifiedLinear })
            }
        }
    }

    @Test
    fun `reified nonnegative zero sums should preserve every Boolean assignment`() {
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
                    listOf(false, false, true),
                    listOf(false, true, true),
                    listOf(true, false, false),
                    listOf(true, true, false),
                ),
                assignments,
            )
        }
    }

    @Test
    fun `signed sums should preserve cancellation between Boolean channels`() {
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
    fun `unreified zero sums should pin only nonzero weighted channels`() {
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

    @Test
    fun `square propagation should retain source witnesses when corner products overflow`() {
        val parsed = parseFlatZinc(
            "var 2900000000..4000000000: x; " +
                "constraint int_times(x, x, 9000000000000000000); solve satisfy;",
        )
        val baked = parsed.problem.bake()
        val variable = parsed.intVarsByName.getValue("x")

        val pins = LocalSearchModel.of(baked).pinsUnder(Assumptions.None.withInt(variable, 3000000000))

        assertNotNull(pins)
    }

    @Test
    fun `binary extrema should retain native operands with result aliases`() {
        for (name in listOf("int_min", "int_max")) {
            val program =
                parseFlatZinc(
                    """
                var 0..2: x;
                var 1..1: y;
                constraint $name(x, y, x);
                solve satisfy;
                    """.trimIndent(),
                )
            val factor = program.problem.factors.filterIsInstance<ArrayMinMax>().single()

            assertEquals(listOf(0, 1), factor.xs.toList())
            assertEquals(0, factor.result)
            val sample = assertIs<SolveResult.Sat>(
                BacktrackSolver(program.problem.bake())
                    .solve(BacktrackParams(randomSeed = 0)),
            ).assignment
            assertEquals(
                sample.ints[0],
                if (name == "int_max") {
                    maxOf(sample.ints[0], 1L)
                } else {
                    minOf(sample.ints[0], 1L)
                },
            )
        }
    }

    @Test
    fun `array extrema and selection should retain correlated native factors`() {
        val program =
            parseFlatZinc(
                """
            var 1..2: i;
            var 0..2: x;
            var 0..2: y;
            var 0..2: m;
            constraint array_int_maximum(m, [x, y, x]);
            constraint array_var_int_element(i, [x, y], m);
            constraint int_eq(x, 0);
            constraint int_eq(y, 2);
            solve satisfy;
                """.trimIndent(),
            )

        assertEquals(1, program.problem.factors.filterIsInstance<ArrayMinMax>().size)
        assertEquals(1, program.problem.factors.filterIsInstance<Element>().single().indexOffset)
        val sample = assertIs<SolveResult.Sat>(
            BacktrackSolver(program.problem.bake())
                .solve(BacktrackParams(randomSeed = 0)),
        ).assignment
        assertEquals(2L, sample.ints[0])
        assertEquals(2L, sample.ints[3])
    }
}
