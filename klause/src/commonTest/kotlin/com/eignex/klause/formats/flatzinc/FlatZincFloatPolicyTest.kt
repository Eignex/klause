package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.intdomain.AbstractIntDomain
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatZincFloatPolicyTest {
    @Test
    fun `planning should retain caller scalar and array float search bounds`() {
        val source = """
            var int: n;
            var float: x;
            array[1..2] of var float: values;
            solve satisfy;
        """.trimIndent()
        val ranges = listOf(-3.0 to 7.0, -3000000.0 to 4000000.0, 0.5 to 0.5)
        for ((lo, hi) in ranges) {
            val model = FlatZincParser(FlatZincLexer(source)).parse()
            val compiler = FlatZincCompiler(
                model,
                unboundedIntLo = -11L,
                unboundedIntHi = 19L,
                unboundedFloatLo = lo,
                unboundedFloatHi = hi,
            )

            val declarations = compiler.floatPlanningDeclarations()

            assertEquals(3, declarations.floatVars.size)
            for (variable in declarations.floatVars.values) {
                assertEquals(lo, variable.lo)
                assertEquals(hi, variable.hi)
            }
            val integer = declarations.intDomains[declarations.intVars.getValue("n")]
            assertEquals(-11L, integer.min)
            assertEquals(19L, integer.max)
        }
    }

    @Test
    fun `exact planning should preserve open floats without overlapping integer ids`() {
        val model = FlatZincParser(
            FlatZincLexer(
                """
                var 1..2: i;
                var float: x;
                var 1..2: j;
                array[1..2] of var float: values;
                var float: alias = x;
                solve satisfy;
                """.trimIndent(),
            ),
        ).parse()
        val compiler = FlatZincCompiler(
            model,
            exactFloats = true,
            unboundedFloatLo = Double.NEGATIVE_INFINITY,
            unboundedFloatHi = Double.POSITIVE_INFINITY,
        )

        val declarations = compiler.floatPlanningDeclarations()

        val floats = declarations.floatVars.values.distinctBy { it.varId }
        assertEquals(3, floats.size)
        assertEquals(5, declarations.intDomains.size)
        assertEquals(0, declarations.realLo.size)
        val integerIds = listOf("i", "j").map { declarations.intVars.getValue(it) }
        for (variable in floats) {
            assertFalse(variable.varId in integerIds)
            assertEquals(Double.NEGATIVE_INFINITY, variable.lo)
            assertEquals(Double.POSITIVE_INFINITY, variable.hi)
        }
        assertEquals(declarations.floatVars.getValue("x"), declarations.floatVars.getValue("alias"))
    }

    @Test
    fun `planning domains should inherit caller storage policy`() {
        val source = """
            var {1, 3, 5}: n;
            var float: x;
            solve satisfy;
        """.trimIndent()
        for (threshold in listOf(0, 1024)) {
            val settings = ProblemSettings(
                storage = DomainStorageSettings(bitsetThreshold = threshold),
                largeSpanThreshold = 123L,
                lpMaxTableauCells = 456L,
                lpCeilingTableauCells = 789L,
            )
            val model = FlatZincParser(FlatZincLexer(source)).parse()
            val compiler = FlatZincCompiler(model, settings = settings)

            val declarations = compiler.floatPlanningDeclarations()
            val program = compiler.compile()

            for (domain in declarations.intDomains) {
                assertEquals(settings.storage, assertIs<AbstractIntDomain>(domain).storage)
            }
            assertEquals(settings, program.problem.settings)
        }
    }

    @Test
    fun `singleton search windows should not certify nonlinear float components`() {
        val source = """
            var float: x;
            var float: y;
            var float: z;
            constraint float_times(x, y, z);
            solve satisfy;
        """.trimIndent()
        for (value in listOf(-0.5, 0.0, 0.5)) {
            val program = parseFlatZinc(source, unboundedFloatLo = value, unboundedFloatHi = value)

            for (name in listOf("x", "y", "z")) {
                val variable = program.floatVarsByName.getValue(name)
                assertFalse(variable.lpOnly)
                assertEquals(value, variable.lo)
                assertEquals(value, variable.hi)
                assertTrue(program.floatRepresentations.getValue(name).approximate)
            }
        }
    }

    @Test
    fun `exact mode should not use fallback windows as finite source choices`() {
        val source = """
            var float: x;
            var float: y;
            var float: z;
            constraint float_times(x, y, z);
            solve satisfy;
        """.trimIndent()

        val program = parseFlatZinc(source, exactFloats = true,
            unboundedFloatLo = 0.5, unboundedFloatHi = 0.5).sourceProgram

        assertEquals(0, program.problem.numIntVars)
        assertTrue(program.problem.realLower.all { it == Double.NEGATIVE_INFINITY })
        assertTrue(program.problem.realUpper.all { it == Double.POSITIVE_INFINITY })
        assertNull(program.problem.factors.filterIsInstance<ContinuousProduct>().single().finiteChoices)
    }

    @Test
    fun `integer images should not enumerate oversized or rounded domains`() {
        for (domain in listOf("0..1000000000000", "{9007199254740993}")) {
            val source = """
                var $domain: n;
                var 0.0..1.0: y;
                var float: denominator;
                constraint int2float(n, denominator);
                constraint float_div(0.75, denominator, y);
                solve satisfy;
            """.trimIndent()

            val exact = parseFlatZinc(source, exactFloats = true).sourceProgram
            assertTrue(exact.floatVarsByName.values.all { it.lpOnly })
            assertNull(exact.problem.factors.filterIsInstance<ContinuousProduct>().single().finiteChoices)
        }
    }

    @Test
    fun `conditional and mixed disjunctions should not provide finite float domains`() {
        val covers = listOf(
            "bool_clause([a, b], [other])",
            "bool_clause([a, b, other], [])",
            "bool_clause([a, other], [])",
            "bool_clause([a, b, true], [])",
            "array_bool_or([a, b], other)",
        )
        for (cover in covers) {
            val source = """
                var 0.0..1.0: x;
                var 0.0..1.0: y;
                var 0.0..1.0: z;
                var bool: a;
                var bool: b;
                var bool: other;
                constraint $cover;
                constraint float_eq_reif(x, 0.125, a);
                constraint float_eq_reif(x, 0.375, b);
                constraint float_eq_reif(y, 0.5, other);
                constraint float_times(x, y, z);
                solve satisfy;
            """.trimIndent()

            val program = parseFlatZinc(source, floatBuckets = 2)

            assertFalse(program.floatVarsByName.getValue("x").lpOnly)
            val exact = parseFlatZinc(source, exactFloats = true).sourceProgram
            assertTrue(exact.floatVarsByName.values.all { it.lpOnly })
            assertNull(exact.problem.factors.filterIsInstance<ContinuousProduct>().single().finiteChoices)
        }
    }

    @Test
    fun `disjunctive choices should count toward the finite expansion limit`() {
        val source = """
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            var 0.0..1.0: z;
            var bool: a;
            var bool: b;
            var bool: duplicate;
            constraint bool_clause([a, b, duplicate], []);
            constraint float_eq_reif(x, 0.125, a);
            constraint float_eq_reif(x, 0.375, b);
            constraint float_eq_reif(x, 0.125, duplicate);
            constraint float_times(x, y, z);
            solve satisfy;
        """.trimIndent()
        for (limit in listOf(4, 5)) {
            val model = FlatZincParser(FlatZincLexer(source)).parse()

            val program = FlatZincCompiler(model, floatBuckets = 2, floatChoiceLimit = limit).compile()

            assertEquals(limit == 5, program.floatVarsByName.getValue("x").lpOnly)
        }
        val model = FlatZincParser(FlatZincLexer(source)).parse()
        val exact = FlatZincCompiler(model, exactFloats = true, floatChoiceLimit = 4).compile().sourceProgram
        assertEquals(3, exact.problem.numRealVars)
        val choices = assertNotNull(exact.problem.factors.filterIsInstance<ContinuousProduct>().single().finiteChoices)
        assertEquals(2, choices.size)
        assertFalse(choices.hasGuards)
        assertEquals(2, exact.problem.factors.filterIsInstance<RealFiniteDomain>().single().size)
    }

    @Test
    fun `default products should preserve finite choices and intermediate values`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x;
            var float: y;
            var float: rate;
            var float: intermediate;
            var float: z :: output_var;
            array[1..1] of var float: values = [x];
            constraint float_times(intermediate, rate, z);
            constraint float_times(values[1], y, intermediate);
            constraint array_float_element(i, [0.125], x);
            constraint array_float_element(i, [0.375], y);
            constraint array_float_element(i, [0.5], rate);
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 2,
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("z = 0.0234375;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `a nonlinear continuation should fall back without rounding unrelated choices`() {
        val program = parseFlatZinc(
            """
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            var 0.0..1.0: intermediate;
            var 0.0..1.0: z;
            var 0.0..1.0: independent;
            var 0.5..0.5: multiplier;
            var 0.0..1.0: answer :: output_var;
            var 0.0..1.0: alias = x;
            var 1..1: i;
            constraint array_float_element(i, [0.125], independent);
            constraint array_float_element(i, [0.375], alias);
            constraint float_times(x, y, intermediate);
            constraint float_times(intermediate, y, z);
            constraint float_times(independent, multiplier, answer);
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 9,
        )

        for (name in listOf("x", "alias", "y", "intermediate", "z")) {
            assertFalse(program.floatVarsByName.getValue(name).lpOnly)
        }
        assertTrue(program.floatVarsByName.getValue("independent").lpOnly)

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("answer = 0.0625;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `an incompatible private grid should not round away original finite membership`() {
        val program = parseFlatZinc(
            """
            var 0.0..1.0: x;
            var 0.0..1.0: y;
            var 0.0..1.0: intermediate;
            var 0.0..1.0: z;
            var 1..1: i;
            constraint array_float_element(i, [0.375], x);
            constraint float_times(x, y, intermediate);
            constraint float_times(intermediate, y, z);
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 2,
        )
        val original = program.sourceProgram

        val privateResult = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Unsat>(privateResult)
        val domain = original.problem.factors.filterIsInstance<RealFiniteDomain>().single()
        assertEquals(original.floatVarsByName.getValue("x").varId, domain.variable)
        assertEquals(0.375, domain.values.single())
        assertEquals(4, original.problem.numRealVars)
    }

    @Test
    fun `finite expansion should count repeated entries and product alternatives`() {
        val cases = listOf("0.125, 0.125, 0.375" to true, "0.125, 0.125, 0.125, 0.375" to false)
        for ((entries, exactExpected) in cases) {
            val model = FlatZincParser(
                FlatZincLexer(
                    """
                    var 1..1: i;
                    var 0.0..1.0: x;
                    var 0.0..1.0: y;
                    var 0.0..1.0: z;
                    constraint array_float_element(i, [$entries], x);
                    constraint float_times(x, y, z);
                    solve satisfy;
                    """.trimIndent(),
                ),
            ).parse()

            val program = FlatZincCompiler(model, floatBuckets = 2, floatChoiceLimit = 5).compile()

            for (name in listOf("x", "y", "z")) {
                assertTrue(program.floatVarsByName.getValue(name).lpOnly == exactExpected)
            }
        }
    }

    @Test
    fun `exact mode should retain source selection beyond the private encoding limit`() {
        for (result in listOf("x", "0.125")) {
            val model = FlatZincParser(
                FlatZincLexer(
                    """
                    var 1..1: i;
                    var float: x;
                    constraint array_float_element(i, [0.125, 0.125, 0.375], $result);
                    solve satisfy;
                    """.trimIndent(),
                ),
            ).parse()

            val program = FlatZincCompiler(model, exactFloats = true, floatChoiceLimit = 2).compile().sourceProgram

            assertTrue(program.floatVarsByName.values.all { it.lpOnly })
            assertEquals(3, program.problem.factors.filterIsInstance<RealElement>().single().size)
        }
    }

    @Test
    fun `finite open floats should retain choices outside the grid clamp`() {
        val program = parseFlatZinc(
            """
            var 1..1: i;
            var float: x :: output_var;
            constraint array_float_element(i, [2000000.0], x);
            solve satisfy;
            """.trimIndent(),
        )

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        assertTrue("x = 2000000.0;" in writeFlatZincSolution(program, assignment))
    }

    @Test
    fun `continuous components without finite choices should use the grid`() {
        val program = parseFlatZinc("var 0.0..1.0: x; constraint float_le(x, 0.5); solve satisfy;", floatBuckets = 2)

        assertFalse(program.floatVarsByName.getValue("x").lpOnly)
    }

    @Test
    fun `unconstrained non-objective floats should not add grid search variables`() {
        val program = parseFlatZinc(
            """
            var float: unused;
            array[1..2] of var float: values;
            solve satisfy;
            """.trimIndent(),
            floatBuckets = 1024,
        )

        assertEquals(0, program.problem.numIntVars)
        assertEquals(3, program.problem.numRealVars)
        assertTrue(program.floatVarsByName.values.all { it.lpOnly })
    }

    @Test
    fun `an unconstrained float objective should keep the default grid policy`() {
        for (goal in listOf("minimize", "maximize")) {
            val program = parseFlatZinc("var 0.0..1.0: x; solve $goal x;", floatBuckets = 2)

            assertFalse(program.floatVarsByName.getValue("x").lpOnly)
            assertEquals(1, program.problem.numIntVars)
        }
    }

    @Test
    fun `finite float lowering should reject constant division by zero`() {
        for (exact in listOf(false, true)) {
            val error = assertFailsWith<FlatZincParseException> {
                parseFlatZinc(
                    """
                    var 1..1: i;
                    var float: x;
                    var float: y;
                    constraint array_float_element(i, [0.0], x);
                    constraint float_div(x, 0.0, y);
                    solve satisfy;
                    """.trimIndent(),
                    exactFloats = exact,
                )
            }

            assertTrue(error.message.orEmpty().contains("division by zero"))
        }
    }
}
