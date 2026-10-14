package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.times
import com.eignex.skema.SchemaDef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductCompilationTest {
    @Test
    fun `compiled squares grade moves against the original source equation`() {
        val x = IntHandle("x", 1, 3)
        val models = listOf(
            "schema" to SchemaDef<SchemaEntry>(mapOf(
                "x" to IntSpec(1, 3), "square" to NamedConstraint((x * x) eq 4),
            )).compile().problem,
            "FlatZinc" to parseFlatZinc(
                "var 1..3: x; constraint int_times(x, x, 4); solve satisfy;",
            ).problem,
            "SMT product" to SmtLib.parse(
                "(declare-const x Int) (assert (and (<= 1 x 3) (= (* x x) 4)))",
            ).model,
            "SMT power" to SmtLib.parse(
                "(declare-const x Int) (assert (and (<= 1 x 3) (= (^ x 2) 4)))",
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
            for (id in 0 until problem.numIntVars) state.assignment.setInt(id, state.rootDomains[id].min)
            for (id in 0 until problem.numBoolVars) state.assignment.setBool(id, true)
            state.assignment.setInt(product.a, 2)
            state.assignment.setInt(product.result, 4)
            state.recompute()
            assertEquals(0L, state.cost, format)
            val move = Move.IntSet(product.a, 3)
            val predicted = state.netDelta(move)

            state.apply(move)
            val committed = state.cost
            state.recompute()

            val sourceResidual = 3L * 3L - 4L
            assertEquals(sourceResidual, predicted, format)
            assertEquals(sourceResidual, committed, format)
            assertEquals(committed, state.cost, format)
            val factorId = problem.factors.indexOf(product)
            assertTrue(state.factors[factorId].isViolated(state, factorId))
            assertFalse(state.assignment.intValue(product.a).let { it * it == 4L }, format)
        }
    }
    @Test
    fun `fixed power chains preserve source grading with pinned outputs`() {
        for ((exponent, expected) in listOf(2 to 4L, 3 to 8L, 4 to 16L)) {
            val parsed = SmtLib.parse(
                "(declare-const x Int) (assert (and (<= 1 x 3) (= (^ x $exponent) $expected)))",
            )
            val problem = parsed.model
            val products = problem.factors.filterIsInstance<Product>()
            val output = products.last().result
            val input = parsed.intVarNames.getValue("x")
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1),
                Assumptions.None.withInt(output, expected))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem)).network(
                problem.numIntVars, problem.numBoolVars,
            )
            for (id in 0 until problem.numIntVars) state.assignment.setInt(id, state.rootDomains[id].min)
            for (id in 0 until problem.numBoolVars) state.assignment.setBool(id, true)
            state.assignment.setInt(input, 2)
            for (product in products) {
                state.assignment.setInt(product.result,
                    state.assignment.intValue(product.a) * state.assignment.intValue(product.b))
            }
            state.recompute()
            assertEquals(0L, state.cost)
            val move = Move.IntSet(input, 3)
            val predicted = state.netDelta(move)

            state.apply(move)
            val committed = state.cost
            state.recompute()

            val sourceValue = when (exponent) { 2 -> 9L; 3 -> 27L; else -> 81L }
            val sourceDegree = compressViolation(sourceValue - expected, state.violationSoftCap).toLong()
            assertEquals(expected, state.assignment.intValue(output))
            assertEquals(sourceDegree, predicted, "exponent $exponent")
            assertEquals(sourceDegree, committed, "exponent $exponent")
            assertEquals(committed, state.cost)
        }
    }

    @Test
    fun `source result aliases retain full grading after compilation`() {
        for ((left, right, result) in listOf(
            Triple("x", "x", "y"), Triple("x", "y", "x"),
            Triple("y", "x", "x"), Triple("x", "x", "x"),
        )) {
            val models = listOf(
                parseFlatZinc("var 0..4: x; var 0..4: y; " +
                    "constraint int_times($left, $right, $result); solve satisfy;").problem,
                Xcsp3.parse("""<instance><variables><var id="x">0..4</var><var id="y">0..4</var>
                    </variables><constraints><intension>eq($result,mul($left,$right))</intension>
                    </constraints></instance>""").problem,
            )
            for (problem in models) {
                val product = problem.factors.filterIsInstance<Product>().single()
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
                state.assignment.setInt(0, 2)
                state.assignment.setInt(1, 4)
                state.recompute()
                val before = kotlin.math.abs((if (left == "x") 2L else 4L) *
                    (if (right == "x") 2L else 4L) - (if (result == "x") 2L else 4L))
                val after = kotlin.math.abs((if (left == "x") 3L else 4L) *
                    (if (right == "x") 3L else 4L) - (if (result == "x") 3L else 4L))
                assertEquals(before, state.cost)
                val predicted = state.netDelta(Move.IntSet(0, 3))

                state.apply(Move.IntSet(0, 3))
                val committed = state.cost
                state.recompute()

                assertEquals(after - before, predicted)
                assertEquals(after, committed)
                assertEquals(after, state.cost)
                assertEquals(if (left == "x") 0 else 1, product.a)
                assertEquals(if (right == "x") 0 else 1, product.b)
                assertEquals(if (result == "x") 0 else 1, product.result)
            }
        }
    }

    @Test
    fun `integer image float squares retain repeated source operands`() {
        for (exact in listOf(false, true)) {
            val program = parseFlatZinc(
                """var 1..3: n; var float: x; var float: z;
                    constraint int2float(n, x); constraint float_times(x, x, z);
                    constraint float_eq(z, 4.0); solve satisfy;""",
                exactFloats = exact,
            )
            val plan = program.problem.prepareExecutionPlan()
            val canonical = assertNotNull(plan.model)
            val projection = assertNotNull(plan.componentPlan).cpProjection(canonical, emptyMap())
            val problem = projection.problem
            val product = problem.factors.filterIsInstance<Product>().single()
            val original = program.problem.factors.filterIsInstance<Product>().single()
            assertEquals(plan.integerCoordinate(original.a), projection.sourceId(product.a))
            assertEquals(plan.integerCoordinate(original.b), projection.sourceId(product.b))
            assertEquals(plan.integerCoordinate(original.result), projection.sourceId(product.result))
            assertEquals(product.a, product.b)
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(1))
            for (id in 0 until problem.numIntVars) state.assignment.setInt(id, state.rootDomains[id].min)
            for (clause in problem.factors.filterIsInstance<Clause>()) {
                if (clause.literals.size != 1) continue
                val literal = clause.literals.single()
                state.assignment.setBool(Lit.variable(literal), Lit.isPositive(literal))
            }
            state.assignment.setInt(product.a, 2)
            state.assignment.setInt(product.result, 4)
            state.recompute()
            assertEquals(0L, state.cost)
            val predicted = state.netDelta(Move.IntSet(product.a, 3))

            state.apply(Move.IntSet(product.a, 3))
            val committed = state.cost
            state.recompute()

            assertEquals(3L * 3L - 4L, predicted)
            assertEquals(5L, committed)
            assertEquals(committed, state.cost)
        }
    }

}
