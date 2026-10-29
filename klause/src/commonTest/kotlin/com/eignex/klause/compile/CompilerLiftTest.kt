package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.model.IntAbs
import com.eignex.klause.model.IntDomainSpec
import com.eignex.klause.model.IntMul
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntScale
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.IntSum
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.abs
import com.eignex.klause.schema.div
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.iff
import com.eignex.klause.schema.le
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.rem
import com.eignex.klause.schema.times
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.skema.SchemaDef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilerLiftTest {
    @Test
    fun `a repeated quotient and remainder alias should reject inconsistent source roles`() {
        val parsed = parseFlatZinc(
            """
            var -7..-7: a; var -3..-3: b; var -4..4: q; var -4..4: both;
            constraint int_div(a,b,q); constraint int_div(a,b,both);
            constraint int_mod(a,b,both); solve satisfy;
            """.trimIndent(),
        )

        val session = PropagationSession(parsed.problem)

        assertTrue(session.isUnsatAtRoot)
    }

    @Test
    fun `repeated FlatZinc division outputs should preserve aliases across compilations`() {
        for ((a, q, r) in listOf(Triple(-7L, 2L, -1L), Triple(7L, -2L, 1L))) {
            val parsed = parseFlatZinc(
                """
                var $a..$a: a; var -3..-3: b; var -3..-3: c;
                var -4..4: q; var -4..4: r; var -4..4: qAlias; var -4..4: rAlias;
                constraint int_div(a,b,q); constraint int_eq(q,q);
                constraint int_mod(a,c,r); constraint int_div(a,c,qAlias);
                constraint int_mod(a,b,rAlias); solve satisfy;
                """.trimIndent(),
            )
            assertEquals(1, parsed.problem.factors.filterIsInstance<DivMod>().size)

            val session = PropagationSession(parsed.problem)

            assertFalse(session.isUnsatAtRoot)
            for ((name, expected) in listOf("q" to q, "qAlias" to q, "r" to r, "rAlias" to r)) {
                assertEquals(IntDomain(expected, expected), session.intDomain(parsed.intVarsByName.getValue(name)))
            }
        }
    }

    @Test
    fun `mul product domain overflow should fail at compile time`() {
        class S : VariableSchema() {
            val x by intVar(min = 0, max = 100_000)
            val y by intVar(min = 0, max = 100_000)
            val cap by constraint { (x * y) le 1 }
        }
        assertFails { S().compile() }
    }

    @Test
    fun `wide integer domain arithmetic should reject overflow before narrowing`() {
        val x = IntRef("x")
        val cases = listOf(
            (1L shl 32) to IntMul(x, x),
            (1L shl 62) to IntAbs(IntScale(4, x)),
            (1L shl 62) to IntAbs(IntSum(listOf(x, x, x, x))),
        )
        for ((value, expression) in cases) {
            val schema = SchemaDef<SchemaEntry>(
                mapOf(
                    "x" to IntDomainSpec(value, value),
                    "constraint" to NamedConstraint(expression eq 0),
                ),
            )

            assertFailsWith<ArithmeticException> { schema.compile() }
        }
    }

    @Test
    fun `wide integer sums should preserve cancellation before narrowing`() {
        val lowering = Lowering(ProblemSettings(), schemaSemantics.resolve())
        for ((name, value) in listOf("x" to Long.MAX_VALUE, "y" to 1L, "z" to -Long.MAX_VALUE)) {
            lowering.bindIntName(name, lowering.newIntVar(IntDomain(value, value)))
        }
        val sum = IntSum(listOf(IntRef("x"), IntRef("y"), IntRef("z")))

        val domain = lowering.domainOf(sum)

        assertEquals(IntDomain(1, 1), domain)
    }

    @Test
    fun `schema reification should preserve both truth values of an absolute value predicate`() {
        class S : VariableSchema() {
            val b by boolVar()
            val x by intVar(-2, 2)
            val relation by constraint { b iff (x.abs() eq 2) }
        }
        val compiled = S().compile()
        assertTrue(compiled.problem.factors.any { it is Abs })
        val x = compiled.intVarIdByName.getValue("x")
        val b = compiled.boolVarIdByName.getValue("b")

        val values = BacktrackSolver(compiled.problem.bake()).enumerate().map {
            it.intValue(x) to it.boolValue(b)
        }.toSet()

        assertEquals(setOf(-2L to true, -1L to false, 0L to false, 1L to false, 2L to true), values)
    }

    @Test
    fun `XCSP distance expressions should retain the absolute value of their affine operand`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="x">-3..3</var><var id="y">1</var></variables>
                <constraints><intension>eq(dist(x,y),2)</intension></constraints></instance>""",
        )
        assertEquals(1, parsed.problem.factors.filterIsInstance<Abs>().size)
        val operand = parsed.intVarNames.getValue("x")

        val values = BacktrackSolver(parsed.problem.bake()).enumerate().map { it.intValue(operand) }.toSet()

        assertEquals(setOf(-1L, 3L), values)
    }

    @Test
    fun `XCSP Boolean scopes should preserve the complete absolute value predicate`() {
        val parsed = Xcsp3.parse(
            """<instance><variables><var id="x">-3..3</var></variables>
                <constraints><intension>or(eq(abs(x),2),eq(x,0))</intension></constraints></instance>""",
        )
        assertEquals(1, parsed.problem.factors.filterIsInstance<Abs>().size)
        val operand = parsed.intVarNames.getValue("x")

        val values = BacktrackSolver(parsed.problem.bake()).enumerate().map { it.intValue(operand) }.toSet()

        assertEquals(setOf(-2L, 0L, 2L), values)
    }

    @Test
    fun `FlatZinc absolute value predicates should solve their retained source relation`() {
        val source = parseFlatZinc("var -3..3: x; constraint int_abs(x, 2); solve satisfy;").problem
        val relation = source.factors.filterIsInstance<Abs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(-2L, 2L), solutions)
    }

    @Test
    fun `bounded SMT absolute value terms should solve their retained source relation`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (and (<= (- 3) x 3) (= (abs x) 2)))").model
        val relation = source.factors.filterIsInstance<Abs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(-2L, 2L), solutions)
    }

    @Test
    fun `absolute value outputs should follow moves through maintained definitions`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-3, 3), IntDomain(0, 3)), arrayOf(Abs(0, 1)))
        val relation = source.factors.filterIsInstance<Abs>().single()
        val sweep = assertNotNull(DefinitionalSweep.infer(source))
        val state = LocalSearchState(LocalSearchModel.open(source), Random(1))
        state.invariants = sweep.network(source.numIntVars, source.numBoolVars)
        state.assignment.setInt(relation.operand, -1L)
        state.assignment.setInt(relation.result, 1L)
        state.recompute()

        state.apply(Move.IntSet(relation.operand, -3L))

        assertEquals(3L, state.assignment.intValue(relation.result))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `truncated variable division should retain shared outputs and source solutions`() {
        val a = IntHandle("a", 0, 8)
        val b = IntHandle("b", 2, 4)
        val schema = SchemaDef<SchemaEntry>(
            mapOf(
                "a" to IntSpec(0, 8),
                "b" to IntSpec(2, 4),
                "quotient" to NamedConstraint((a / b) eq 2),
                "remainder" to NamedConstraint((a % b) eq 1),
            ),
        ).compile().problem
        val fzn = parseFlatZinc(
            """
            var 0..8: a; var 2..4: b;
            constraint int_div(a,b,2); constraint int_mod(a,b,1); solve satisfy;
            """.trimIndent(),
        ).problem
        val xcsp = Xcsp3.parse(
            """
            <instance><variables><var id="a">0..8</var><var id="b">2..4</var></variables>
            <constraints><intension>eq(div(a,b),2)</intension><intension>eq(mod(a,b),1)</intension>
            </constraints></instance>
            """.trimIndent(),
        ).problem
        for ((format, problem) in listOf("schema" to schema, "FlatZinc" to fzn, "XCSP3" to xcsp)) {
            assertEquals(1, problem.factors.filterIsInstance<DivMod>().size, format)
            assertFalse(problem.factors.any { it is Product }, format)
            val samples = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
                .map { it.intValue(0) to it.intValue(1) }.toSet()

            assertEquals(setOf(5L to 2L, 7L to 3L), samples, format)
        }
    }

    @Test
    fun `affine operands should retain one correlated relation across quotient and remainder uses`() {
        val a = IntHandle("a", 0, 8)
        val b = IntHandle("b", 2, 4)
        val schema = SchemaDef<SchemaEntry>(
            mapOf(
                "a" to IntSpec(0, 8),
                "b" to IntSpec(2, 4),
                "quotient" to NamedConstraint(((a + 1) / b) eq 2),
                "remainder" to NamedConstraint(((a + 1) % b) eq 1),
            ),
        ).compile().problem
        val xcsp = Xcsp3.parse(
            """
            <instance><variables><var id="a">0..8</var><var id="b">2..4</var></variables>
            <constraints><intension>eq(div(add(a,1),b),2)</intension>
            <intension>eq(mod(add(a,1),b),1)</intension></constraints></instance>
            """.trimIndent(),
        ).problem

        for (problem in listOf(schema, xcsp)) assertEquals(1, problem.factors.filterIsInstance<DivMod>().size)
    }

    @Test
    fun `SMT division should retain a shared Euclidean relation with a negative divisor`() {
        val parsed = SmtLib.parse(
            """
            (declare-const a Int) (assert (<= (- 8) a 8))
            (assert (= (div a (- 3)) 3)) (assert (= (mod a (- 3)) 2))
            """.trimIndent(),
        )
        val factor = parsed.model.factors.filterIsInstance<DivMod>().single()
        val samples = BacktrackSolver(parsed.model.bake()).enumerate(BacktrackParams(maxDecisions = 100))
            .map { it.intValue(parsed.intVarNames.getValue("a")) }.toSet()

        assertEquals(DivisionConvention.EUCLIDEAN, factor.convention)
        assertEquals(setOf(-7L), samples)
    }

    @Test
    fun `FlatZinc output aliases should retain their source role equations`() {
        for ((operation, expected) in listOf("div" to setOf(0L), "mod" to (-2L..2L).toSet())) {
            val parsed = parseFlatZinc(
                "var -4..4: a; constraint int_$operation(a,3,a); solve satisfy;",
            )
            val assignments = BacktrackSolver(parsed.problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
                .map { it.intValue(parsed.intVarsByName.getValue("a")) }.toSet()

            assertEquals(expected, assignments, operation)
        }
    }

    @Test
    fun `signed FlatZinc constant division should retain truncation`() {
        val parsed =
            parseFlatZinc(
                """
            var -8..8: a; constraint int_div(a,-3,2); constraint int_mod(a,-3,-1); solve satisfy;
                """.trimIndent(),
            )
        val samples = BacktrackSolver(parsed.problem.bake()).enumerate(BacktrackParams(maxDecisions = 100))
            .map { it.intValue(parsed.intVarsByName.getValue("a")) }.toSet()

        assertEquals(setOf(-7L), samples)
    }

    @Test
    fun `compiled squares should grade moves against the original source equation`() {
        val x = IntHandle("x", 1, 3)
        val models = listOf(
            "schema" to SchemaDef<SchemaEntry>(
                mapOf(
                    "x" to IntSpec(1, 3),
                    "square" to NamedConstraint((x * x) eq 4),
                ),
            ).compile().problem,
            "FlatZinc" to parseFlatZinc(
                "var 1..3: x; constraint int_times(x, x, 4); solve satisfy;",
            ).problem,
            "SMT product" to SmtLib.parse(
                "(declare-const x Int) (assert (and (<= 1 x 3) (= (* x x) 4)))",
            ).model,
            "XCSP3" to Xcsp3.parse(
                """<instance><variables><var id="x">1..3</var></variables>
                    <constraints><intension>eq(mul(x,x),4)</intension></constraints></instance>""",
            ).problem,
        )
        for ((format, problem) in models) {
            val product = problem.factors.filterIsInstance<Product>().single()
            assertEquals(product.a, product.b, format)
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            state.assignMinimums(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(product.a, 2)
            state.assignment.setInt(product.result, 4)
            state.recompute()

            val (predicted, committed) = state.gradeMove(Move.IntSet(product.a, 3))

            assertEquals(3L * 3L - 4L, predicted, format)
            assertEquals(3L * 3L - 4L, committed, format)
        }
    }

    @Test
    fun `native powers should preserve source grading with pinned outputs`() {
        for ((exponent, expected) in listOf(2 to 4L, 3 to 8L)) {
            val parsed = SmtLib.parse(
                "(declare-const x Int) (assert (and (<= 1 x 3) (= (^ x $exponent) $expected)))",
            )
            val problem = parsed.model
            val power = problem.factors.filterIsInstance<Power>().single()
            val output = (power.result as Power.Coordinate.Integer).id.index
            val input = parsed.intVarNames.getValue("x")
            val state = LocalSearchState(
                LocalSearchModel.open(problem),
                Random(1),
                Assumptions.None.withInt(output, expected),
            )
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem)).network(
                problem.numIntVars,
                problem.numBoolVars,
            )
            state.assignMinimums(problem.numIntVars, problem.numBoolVars)
            state.assignment.setInt(input, 2)
            state.assignment.setInt(output, expected)
            state.recompute()

            val (predicted, committed) = state.gradeMove(Move.IntSet(input, 3))

            val sourceValue = if (exponent == 2) 9L else 27L
            val sourceDegree = compressViolation(sourceValue - expected, state.violationSoftCap).toLong()
            assertEquals(expected, state.assignment.intValue(output))
            assertEquals(sourceDegree, predicted, "exponent $exponent")
            assertEquals(sourceDegree, committed, "exponent $exponent")
        }
    }

    @Test
    fun `source result aliases should retain full grading after compilation`() {
        for ((left, right, result) in listOf(
            Triple("x", "x", "y"),
            Triple("x", "y", "x"),
            Triple("y", "x", "x"),
            Triple("x", "x", "x"),
        )) {
            val x = IntHandle("x", 0, 4)
            val y = IntHandle("y", 0, 4)
            fun handle(name: String) = if (name == "x") x else y
            fun value(name: String, xValue: Long) = if (name == "x") xValue else 4L
            fun residual(xValue: Long) = kotlin.math.abs(
                value(left, xValue) * value(right, xValue) - value(result, xValue),
            )
            val models = listOf(
                SchemaDef<SchemaEntry>(
                    mapOf(
                        "x" to IntSpec(0, 4),
                        "y" to IntSpec(0, 4),
                        "product" to NamedConstraint((handle(left) * handle(right)) eq handle(result)),
                    ),
                ).compile().problem,
                SmtLib.parse(
                    "(declare-const x Int) (declare-const y Int) " +
                        "(assert (and (<= 0 x 4) (<= 0 y 4) (= (* $left $right) $result)))",
                ).model,
                parseFlatZinc(
                    "var 0..4: x; var 0..4: y; " +
                        "constraint int_times($left, $right, $result); solve satisfy;",
                ).problem,
                Xcsp3.parse(
                    """<instance><variables><var id="x">0..4</var><var id="y">0..4</var>
                    </variables><constraints><intension>eq($result,mul($left,$right))</intension>
                    </constraints></instance>""",
                ).problem,
            )
            for (problem in models) {
                val product = problem.factors.filterIsInstance<Product>().single()
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
                state.assignMinimums(problem.numIntVars, problem.numBoolVars)
                state.assignment.setInt(0, 2)
                state.assignment.setInt(1, 4)
                if (product.result >= 2) {
                    state.assignment.setInt(
                        product.result,
                        state.assignment.intValue(product.a) * state.assignment.intValue(product.b),
                    )
                }
                state.recompute()
                assertEquals(residual(2), state.cost)

                val (predicted, committed) = state.gradeMove(Move.IntSet(0, 3))

                assertEquals(residual(3) - residual(2), predicted)
                assertEquals(residual(3), committed)
            }
        }
    }

    @Test
    fun `integer image float squares should retain repeated source operands`() {
        for (exact in listOf(false, true)) {
            val program = parseFlatZinc(
                """var 1..3: n; var float: x; var float: z;
                    constraint int2float(n, x); constraint float_times(x, x, z);
                    constraint float_eq(z, 4.0); solve satisfy;""",
                exactFloats = exact,
            )
            val plan = program.problem.prepareExecutionPlan()
            val problem = assertNotNull(plan.polynomialModel).finite
            val product = problem.factors.filterIsInstance<Product>().single()
            assertEquals(product.a, product.b)
            val scoring = assertNotNull(plan.finiteFactorProjections)
            val state = LocalSearchState(LocalSearchModel.open(problem, scoring), Random(1))
            val session = PropagationSession(PropagationProblem(problem, scoring))
            session.pinInt(product.a, 2)
            session.pinInt(product.result, 4)
            state.assignMinimums(problem.numIntVars, 0)
            for (id in 0 until problem.numBoolVars) {
                state.assignment.setBool(id, session.boolValue(id) ?: false)
            }
            state.assignment.setInt(product.a, 2)
            state.assignment.setInt(product.result, 4)
            state.recompute()

            val (predicted, committed) = state.gradeMove(state.synthesizeChannelingMove(product.a, 3))

            assertEquals(3L * 3L - 4L, state.factorDegree[problem.factors.indexOf(product)].toLong())
            assertEquals(committed, predicted)
        }
    }

    private fun LocalSearchState.assignMinimums(numIntVars: Int, numBoolVars: Int) {
        for (id in 0 until numIntVars) assignment.setInt(id, rootDomains[id].min)
        for (id in 0 until numBoolVars) assignment.setBool(id, true)
    }

    private fun LocalSearchState.gradeMove(move: Move): Pair<Long, Long> {
        val predicted = netDelta(move)
        apply(move)
        val committed = cost
        recompute()
        assertEquals(committed, cost)
        return predicted to committed
    }
}
