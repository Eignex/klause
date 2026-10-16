package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.FactorWeightBook
import com.eignex.klause.localsearch.IMPLIED_FACTOR_INITIAL_WEIGHT
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.perCharSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlatZincCompilerTest {
    @Test
    fun `integer set aliases should intersect the original domain`() {
        val cases = listOf(
            "1..3" to "3, 1, 3, 4",
            "{1, 3, 5}" to "1, 2, 3",
            "1..5" to "1, 3",
        )
        for ((domain, values) in cases) {
            val program = parseFlatZinc("var $domain: x; var {$values}: y = x; solve satisfy;")

            val declared = program.problem.declaredIntDomains.finiteDomain(0).span()
            assertEquals(listOf(1L, 3L), List(declared.size) { declared.valueAt(it) })
        }
    }

    @Test
    fun `integer set aliases should reject excluded pinned values`() {
        for (declaration in listOf("var {1, 3}: y = x;", "var {1, 2}: y = x; var {1, 3}: z = y;")) {
            val program = parseFlatZinc("var 1..3: x; $declaration constraint int_eq(x, 2); solve satisfy;")

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            assertIs<SolveResult.Unsat>(result)
        }
    }

    @Test
    fun `disjoint integer alias domains should be unsatisfiable`() {
        val program = parseFlatZinc("var {1, 3}: x; var {2, 4}: y = x; solve satisfy;")

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `explicit integer sets should preserve their distinct values across wide gaps`() {
        val cases = listOf(
            "32461759, 8, 0, 8" to listOf(0L, 8L, 32461759L),
            "8, -1000000000000, 0, 8" to listOf(-1000000000000L, 0L, 8L),
            "9223372036854775807" to listOf(Long.MAX_VALUE),
        )
        for ((entries, expected) in cases) {
            for (declaration in listOf("var {$entries}: x;", "array[1..2] of var {$entries}: x;")) {
                val program = parseFlatZinc("$declaration solve satisfy;")

                for (id in 0 until program.problem.numIntVars) {
                    val domain = program.problem.declaredIntDomains.finiteDomain(id)
                    val values = domain.span()
                    assertEquals(expected, List(values.size) { values.valueAt(it) })
                    assertEquals(expected.first(), domain.min)
                    assertEquals(expected.last(), domain.max)
                }
            }
        }
    }

    @Test
    fun `a fixed set variable should pin exactly its declared membership indicators`() {
        val program = parseFlatZinc("var set of {1, 2, 3}: s = {1, 3}; solve satisfy;")

        val layout = program.setVarsByName.getValue("s")
        val factors = program.problem.factors.map(::assertIsClause)
        assertEquals(listOf(1, 2, 3), layout.elements.toList())
        assertEquals(3, factors.size)
        assertEquals(
            listOf(true, false, true),
            factors.map { Lit.isPositive(it.literals.single()) },
        )
        assertEquals(layout.indicatorBoolIds.toList(), factors.map { Lit.variable(it.literals.single()) })
    }

    @Test
    fun `duplicate declarations should be rejected`() {
        val error = assertFailsWith<FlatZincParseException> {
            parseFlatZinc("var bool: x; var 0..1: x; solve satisfy;")
        }

        assertTrue(error.message.orEmpty().contains("duplicate declaration of `x`"))
    }

    @Test
    fun `trailing declarations after solve should be rejected`() {
        assertFailsWith<FlatZincParseException> {
            parseFlatZinc("solve satisfy; var bool: x;")
        }
    }

    @Test
    fun `an uninitialized parameter should be rejected`() {
        assertFailsWith<FlatZincParseException> {
            parseFlatZinc("par 1..2: limit; solve satisfy;")
        }
    }

    @Test
    fun `nonfinite float literals should be rejected with a located format error`() {
        val error = assertFailsWith<FlatZincParseException> {
            parseFlatZinc("var 0.0..1e999: x; solve satisfy;")
        }

        assertTrue(error.message.orEmpty().contains("outside the finite range"))
    }

    private fun assertIsClause(factor: com.eignex.klause.ir.Factor): Clause = assertIs<Clause>(factor)

    @Test
    fun `a semantic compile error should report the constraint source line`() {
        val src = "var 1..3: x;\nvar 1..3: y;\nconstraint not_a_real_builtin(x, y);\nsolve satisfy;"
        val e = assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        assertTrue("at 3:" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `float_div with the wrong arity should be a parse error`() {
        val src = "var 0.0..1.0: a;\nvar 0.0..1.0: b;\nconstraint float_div(a, b);\nsolve satisfy;"
        assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
    }

    @Test
    fun `constraint emitters should surface a wrong arity as a parse error`() {
        val cases = listOf(
            "var bool: b;\nconstraint bool_eq(b);\nsolve satisfy;",
            "var 1..3: x;\nconstraint int_eq(x);\nsolve satisfy;",
            "var 1..3: x;\nconstraint all_different_int([x], [x]);\nsolve satisfy;",
            "var 0.0..1.0: f;\nconstraint float_eq(f);\nsolve satisfy;",
            "var set of 1..3: s;\nconstraint set_subset(s);\nsolve satisfy;",
            "var set of 1..3: s;\nconstraint set_card(s);\nsolve satisfy;",
        )
        for (src in cases) {
            assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        }
    }

    @Test
    fun `an out-of-bounds parameter array access should be a parse error`() {
        val src = """
            array[1..3] of int: a = [10, 20, 30];
            var 1..9: x;
            constraint int_lin_le([1], [x], a[5]);
            solve satisfy;
        """.trimIndent()
        val e = assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        assertTrue("out of bounds" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `an out-of-range array length should report a FlatZinc error`() {
        val src = "array[1..3000000000] of var int: a;\nsolve satisfy;"
        val e = assertFailsWith<FlatZincParseException> { parseFlatZinc(src) }
        assertTrue("array length out of range" in e.message.orEmpty(), e.message.orEmpty())
    }

    @Test
    fun `uncovered constructs should be declined as unsupported`() {
        val cases = listOf(
            "var 1..3: x;\nconstraint not_a_real_builtin(x);\nsolve satisfy;",
        )
        for (src in cases) assertFailsWith<UnsupportedFlatZincException>(src) { parseFlatZinc(src) }
    }

    @Test
    fun `a malformed instance should be a parse error rather than unsupported`() {
        val e = assertFailsWith<FlatZincParseException> {
            parseFlatZinc(
                "var bool: b;\nconstraint bool2int(b);\nsolve satisfy;",
            )
        }

        assertFalse(e is UnsupportedFlatZincException)
    }

    @Test
    fun `float arrays should preserve references and constant values`() {
        val initializers = listOf("[x, 72.0]", "[x, c]", "[x, constants[1]]", "[source[1], 72.0]")
        for ((initializer, exact) in initializers.flatMap { listOf(it to false, it to true) }) {
            val program = parseFlatZinc(
                """
                float: c = 72.0;
                array[1..1] of float: constants = [72.0];
                var 0.0..4.0: x;
                array[1..1] of var float: source = [x];
                array[1..2] of var float: values :: output_array([1..2]) = $initializer;
                constraint float_lin_eq([1.0, 1.0], values, 74.0);
                solve satisfy;
                """.trimIndent(),
                floatBuckets = 5,
                exactFloats = exact,
            )

            val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

            val assignment = assertIs<SolveResult.Sat>(result, initializer).assignment
            val output = writeFlatZincSolution(program, assignment)
            assertTrue("values = [2.0, 72.0];" in output, "$initializer: $output")
        }
    }

    @Test
    fun `open scalar and array floats should use the configured search range`() {
        for (declaration in listOf("var float: x;", "array[1..2] of var float: x;")) {
            val program = parseFlatZinc(
                "$declaration\n" + if (declaration.startsWith("array")) {
                    "constraint float_le(x[1], 0.0); constraint float_le(x[2], 0.0); solve satisfy;"
                } else {
                    "constraint float_le(x, 0.0); solve satisfy;"
                },
                unboundedFloatLo = -3.0,
                unboundedFloatHi = 7.0,
                floatBuckets = 5,
            )

            for (float in program.floatVarsByName.values) {
                assertEquals(-3.0, float.valueOf(0))
                assertEquals(7.0, float.valueOf(4))
            }
            assertEquals(0, program.problem.numRealVars)
        }
    }

    @Test
    fun `exact float declarations should retain open bounds`() {
        for (declaration in listOf("var float: x;", "array[1..2] of var float: x;")) {
            val program = parseFlatZinc("$declaration\nsolve satisfy;", exactFloats = true)

            assertEquals(0, program.problem.numIntVars)
            assertTrue(program.problem.realLower.all { it == Double.NEGATIVE_INFINITY })
            assertTrue(program.problem.realUpper.all { it == Double.POSITIVE_INFINITY })
        }
    }

    @Test
    fun `exact float intake should retain an unrestricted original product`() {
        val program = parseFlatZinc(
            "var -1.0..1.0: x; constraint float_times(x, x, x); solve satisfy;",
            exactFloats = true,
        ).sourceProgram

        val product = program.problem.factors.filterIsInstance<ContinuousProduct>().single()
        assertEquals(0, program.problem.numIntVars)
        assertEquals(1, program.problem.numRealVars)
        assertNull(product.finiteChoices)
        assertEquals(product.a, product.b)
        assertEquals(product.a, product.result)
    }

    @Test
    fun `unrepresentable private scaling should preserve original linear arithmetic`() {
        val program = parseFlatZinc(
            "var 0.0..1.0: x; constraint float_lin_le([1e20], [x], 1.0); solve satisfy;",
        )

        assertTrue(program.floatVarsByName.getValue("x").lpOnly)
        assertEquals(0, program.problem.numIntVars)
        val row = program.sourceProgram.problem.factors.filterIsInstance<Linear>().single()
        assertEquals(1e20, row.realConstants?.realCoefficients?.at(0))
    }

    @Test
    fun `an unannotated model should have no implied mask and no symmetry flag`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        assertNull(problem.impliedFactorMask)
        assertFalse(problem.hasSymmetryBreaking)
    }

    @Test
    fun `a redundant annotation should mark the factor implied without setting the symmetry flag`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            constraint int_lin_eq([1, -1], [x, y], 0) :: klause_redundant;
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        val mask = problem.impliedFactorMask
        assertTrue(mask != null && mask.size == problem.numFactors)
        // The structural inequality stays at full weight; only the redundant equality is implied.
        assertFalse(mask[0])
        assertTrue(mask[1])
        assertFalse(problem.hasSymmetryBreaking)
    }

    @Test
    fun `a symmetry annotation should mark the factor implied and set the symmetry flag`() {
        val src = """
            var 0..10: x;
            var 0..10: y;
            constraint int_lin_le([1, 1], [x, y], 5);
            constraint int_lin_le([1, -1], [x, y], 0) :: klause_symmetry;
            solve satisfy;
        """.trimIndent()
        val problem = parseFlatZinc(src).problem
        val mask = problem.impliedFactorMask
        assertTrue(mask != null && mask.size == problem.numFactors)
        assertFalse(mask[0])
        assertTrue(mask[1])
        assertTrue(problem.hasSymmetryBreaking)
    }

    @Test
    fun `parse should not depend on source chunk boundaries`() {
        val text = "var 0..4: x;\nvar 0..4: y;\nconstraint int_lin_le([1,1],[x,y],4);\nsolve satisfy;\n"
        fun shape(source: CharSource) =
            parseFlatZinc(source).problem.let { Triple(it.numBoolVars, it.numIntVars, it.factors.size) }
        assertEquals(shape(StringCharSource(text)), shape(perCharSource(text)))
    }

    @Test
    fun `bool clause model should parse and solve`() {
        val src = """
            var bool: x;
            var bool: y;
            constraint bool_clause([x, y], []);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(2, program.problem.numBoolVars)
        assertEquals(SolveDirective.Satisfy, program.solve)
        val r = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0] || sat.assignment.bools[1])
    }

    @Test
    fun `solve minimize should reference the int objective`() {
        val src = """
            var 1..10: cost;
            constraint int_lin_ge([1], [cost], 3);
            solve minimize cost;
        """.trimIndent()
        val program = parseFlatZinc(
            src.replace("int_lin_ge", "int_lin_le").replace("[1]", "[-1]").replace(", 3", ", -3"),
        )
        val solve = assertIs<SolveDirective.Minimize>(program.solve)
        assertEquals("cost", solve.objVar)
        assertEquals(SolveDirective.ObjKind.Int, solve.kind)
    }

    @Test
    fun `unsupported builtin should throw a parse exception`() {
        val src = """
            var 0..5: x;
            constraint not_a_real_builtin(x);
            solve satisfy;
        """.trimIndent()
        try {
            parseFlatZinc(src)
            error("expected FlatZincParseException")
        } catch (e: FlatZincParseException) {
            assertTrue(e.message!!.contains("not_a_real_builtin"), "got: ${e.message}")
        }
    }

    @Test
    fun `klause_enum_labels annotation should populate enumLabelsByVar`() {
        val src = """
            var 1..3: color :: klause_enum_labels(["Red","Green","Blue"]);
            solve satisfy;
        """.trimIndent()
        val program = parseFlatZinc(src)
        assertEquals(listOf("Red", "Green", "Blue"), program.enumLabelsByVar["color"])
    }

    @Test
    fun `annotation wrappers should retain source contradictions under either search hint`() {
        for (name in listOf(
            "redundant_constraint", "fzn_redundant_constraint",
            "symmetry_breaking_constraint", "fzn_symmetry_breaking_constraint",
        )) {
            for (forLocalSearch in listOf(false, true)) {
                val program = parseFlatZinc(
                    "var bool: x = false; constraint $name(x); solve satisfy;",
                    forLocalSearch = forLocalSearch,
                )

                val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 0L))

                assertIs<SolveResult.Unsat>(result)
            }
        }
    }

    @Test
    fun `annotation wrappers should retain the local search implied weight`() {
        for (name in listOf(
            "redundant_constraint", "fzn_redundant_constraint",
            "symmetry_breaking_constraint", "fzn_symmetry_breaking_constraint",
        )) {
            for (forLocalSearch in listOf(false, true)) {
                val program = parseFlatZinc(
                    "var bool: x; constraint $name(x); solve satisfy;",
                    forLocalSearch = forLocalSearch,
                )

                val weights = FactorWeightBook(program.problem).factorWeights

                assertEquals(listOf(IMPLIED_FACTOR_INITIAL_WEIGHT), weights.toList())
            }
        }
    }

    @Test
    fun `symmetry wrappers should retain their source symmetry flag`() {
        for (name in listOf("symmetry_breaking_constraint", "fzn_symmetry_breaking_constraint")) {
            for (forLocalSearch in listOf(false, true)) {
                val program = parseFlatZinc(
                    "var bool: x; constraint $name(x); solve satisfy;",
                    forLocalSearch = forLocalSearch,
                )

                assertTrue(program.problem.hasSymmetryBreaking)
            }
        }
    }

    @Test
    fun `redundant wrappers should leave source symmetry unclaimed`() {
        for (name in listOf("redundant_constraint", "fzn_redundant_constraint")) {
            for (forLocalSearch in listOf(false, true)) {
                val program = parseFlatZinc(
                    "var bool: x; constraint $name(x); solve satisfy;",
                    forLocalSearch = forLocalSearch,
                )

                assertFalse(program.problem.hasSymmetryBreaking)
            }
        }
    }

    @Test
    fun `annotation wrapper arity should be validated under either search hint`() {
        for (name in listOf(
            "redundant_constraint", "fzn_redundant_constraint",
            "symmetry_breaking_constraint", "fzn_symmetry_breaking_constraint",
        )) {
            for (forLocalSearch in listOf(false, true)) {
                assertFailsWith<FlatZincParseException> {
                    parseFlatZinc("constraint $name(); solve satisfy;", forLocalSearch = forLocalSearch)
                }
            }
        }
    }
}
