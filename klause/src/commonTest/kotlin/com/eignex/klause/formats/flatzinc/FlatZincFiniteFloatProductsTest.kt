package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincFiniteFloatProductsTest {
    @Test
    fun `finite products should retain source operand order and declared choices`() {
        for (operands in listOf("x, y", "y, x")) {
            val program =
                parseFlatZinc(
                    """
                var 1..2: i;
                var float: x;
                var float: y;
                var float: z;
                constraint array_float_element(i, [0.125, 0.375], x);
                constraint float_times($operands, z);
                solve satisfy;
                    """.trimIndent(),
                    exactFloats = true,
                )

            val product = program.problem.factors.filterIsInstance<ContinuousProduct>().single()

            val names = operands.split(", ")
            assertEquals(program.floatVarsByName.getValue(names[0]).varId, product.a)
            assertEquals(program.floatVarsByName.getValue(names[1]).varId, product.b)
            assertEquals(2, product.finiteChoices?.size)
            assertTrue(product.finiteChoices?.hasGuards == true)
        }
    }

    @Test
    fun `unrepresentable integer float operands should decline before rounding`() {
        for (value in listOf(9007199254740993L, Long.MAX_VALUE)) {
            val sources = listOf(
                "constraint float_times($value, 1.0, 0.0); solve satisfy;",
                "int: n = $value; constraint float_times(n, 1.0, 0.0); solve satisfy;",
            )
            for (source in sources) {
                assertFailsWith<UnsupportedFlatZincException> { parseFlatZinc(source, exactFloats = true) }
            }
        }
    }

    @Test
    fun `literal products should not round exact source equalities`() {
        for ((a, output, valid) in listOf(Triple(0.5, 0.25, true), Triple(0.1, 0.010000000000000002, false))) {
            val program = parseFlatZinc(
                "constraint float_times($a, $a, $output); solve satisfy;",
                exactFloats = true,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            if (valid) assertIs<SolveResult.Sat>(result) else assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `finite integer denominators should keep quotients continuous`() {
        for (exact in listOf(false, true)) {
            val program = parseFlatZinc(
                """
                var {0, 2, 3}: n :: output_var;
                var float: denominator;
                var 0.0..1.0: quotient :: output_var;
                constraint float_div(0.75, denominator, quotient);
                constraint int2float(n, denominator);
                constraint int_eq(n, 2);
                solve satisfy;
                """.trimIndent(),
                floatBuckets = 2,
                exactFloats = exact,
            )

            assertTrue(program.floatVarsByName.getValue("quotient").lpOnly)
            assertTrue(program.floatVarsByName.getValue("denominator").lpOnly)
        }
    }

    @Test
    fun `a variable zero denominator should not divide zero`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: denominator;
            var float: quotient;
            constraint float_div(0.0, denominator, quotient);
            constraint array_float_element(i, [0.0], denominator);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `disjunctions should preserve finite products through float and boolean aliases`() {
        val program = parseFlatZinc(
            """
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            var 0.0..1.0: z :: output_var;
            array[1..1] of var float: values = [x];
            var bool: a;
            var bool: b;
            var bool: alias = b;
            array[1..2] of var bool: guards = [a, b];
            constraint array_bool_or(guards, true);
            constraint float_times(y, values[1], z);
            constraint float_eq(y, 0.5);
            constraint bool_eq(b, true);
            constraint float_eq_reif(0.125, x, guards[1]);
            constraint float_eq_reif(values[1], 0.375, alias);
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 2,
            exactFloats = true,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("z = 0.1875;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `disjoint disjunction and array choices should be unsatisfiable`() {
        val program = parseFlatZinc(
            """
            var float: x;
            var bool: a;
            var bool: b;
            var 1..1: i;
            constraint bool_clause([a, b], []);
            constraint array_float_element(i, [0.5], x);
            constraint float_eq_reif(x, 0.125, a);
            constraint float_eq_reif(x, 0.375, b);
            solve satisfy;
            """.trimIndent(),
        )

        assertIs<PropagationResult.Unsat>(program.problem.baked)
    }

    @Test
    fun `selecting a finite float value should exclude the other choices during propagation`() {
        for (count in listOf(3, 6)) {
            val entries = (1..count).joinToString(", ") { "$it.0" }
            val sources = listOf(
                "var 1..1: i; var float: x; constraint array_float_element(i, [$entries], x); solve satisfy;",
                "var 1..$count: n; var float: x; constraint int2float(n, x); constraint int_eq(n, 1); solve satisfy;",
            )
            for (source in sources) {
                val model = FlatZincParser(FlatZincLexer(source)).parse()
                val compiler = FlatZincCompiler(model)
                val program = compiler.compile()

                val deductions = assertIs<PropagationResult.Implied>(program.problem.baked)
                for ((key, literal) in compiler.floatValueLiterals) {
                    val pinned = deductions.boolValueOrNull(Lit.variable(literal))
                    assertEquals(key.second == 1.0, pinned?.let { Lit.evaluate(literal, it) })
                }
            }
        }
    }

    @Test
    fun `signed zeros should share a finite choice`() {
        val program = parseFlatZinc(
            """
            var 1..2: i;
            var float: x;
            constraint array_float_element(i, [0.0, -0.0], x);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Sat>(result)
    }

    @Test
    fun `disjoint constant arrays should have no common finite float choice`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            constraint array_float_element(i, [1.0], x);
            constraint array_float_element(i, [2.0], x);
            solve satisfy;
            """.trimIndent(),
        )

        assertIs<PropagationResult.Unsat>(program.problem.baked)
    }

    @Test
    fun `products should preserve selected float values through array aliases in either operand`() {
        for (operands in listOf("values[1], y", "y, values[1]")) {
            val program = parseFlatZinc(
                """
                var 1..2: i = 2;
                var 0.0..1.0: x;
                array[1..1] of var float: values = [x];
                var 0.0..1.0: y;
                var 0.0..1.0: z :: output_var;
                constraint float_times($operands, z);
                constraint array_float_element(i, [0.125, 0.375], x);
                constraint float_eq(y, 0.5);
                solve satisfy;
                """.trimIndent(),
                floatBuckets = 2,
                exactFloats = true,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result).assignment
            assertTrue("z = 0.1875;" in writeFlatZincSolution(program, assignment))
        }
    }

    @Test
    fun `a product chain should retain exact intermediate values`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            var float: y;
            var float: rate;
            var float: intermediate;
            var float: z :: output_var;
            constraint float_times(intermediate, rate, z);
            constraint float_times(x, y, intermediate);
            constraint array_float_element(i, [0.125], x);
            constraint array_float_element(i, [0.375], y);
            constraint array_float_element(i, [0.5], rate);
            solve satisfy;
            """.trimIndent(),
            exactFloats = true,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("z = 0.0234375;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `a singleton float domain should support a constant product result`() {
        for (exact in listOf(false, true)) {
            val program = parseFlatZinc(
                """
                var 2.0..2.0: x;
                var float: y :: output_var;
                constraint float_times(x, y, 6.0);
                solve satisfy;
                """.trimIndent(),
                exactFloats = exact,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result).assignment
            assertTrue("y = 3.0;" in writeFlatZincSolution(program, assignment))
        }
    }
}
