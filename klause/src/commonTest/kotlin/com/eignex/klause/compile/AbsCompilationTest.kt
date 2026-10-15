package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.abs
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.iff
import com.eignex.skema.SchemaDef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsCompilationTest {
    @Test
    fun `schema reification preserves both truth values of an absolute value predicate`() {
        class S : VariableSchema() {
            val b by boolVar()
            val x by intVar(-2, 2)
            val relation by constraint { b iff (abs(x) eq 2) }
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
    fun `XCSP distance expressions retain the absolute value of their affine operand`() {
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
    fun `XCSP Boolean scopes preserve the complete absolute value predicate`() {
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
    fun `schema absolute value expressions solve their retained source relation`() {
        val x = IntHandle("x", -3, 3)
        val source = SchemaDef<SchemaEntry>(mapOf(
            "x" to IntSpec(-3, 3), "magnitude" to NamedConstraint(abs(x) eq 2),
        )).compile().problem
        val relation = source.factors.filterIsInstance<Abs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(-2L, 2L), solutions)
    }

    @Test
    fun `FlatZinc absolute value predicates solve their retained source relation`() {
        val source = parseFlatZinc("var -3..3: x; constraint int_abs(x, 2); solve satisfy;").problem
        val relation = source.factors.filterIsInstance<Abs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(-2L, 2L), solutions)
    }

    @Test
    fun `bounded SMT absolute value terms solve their retained source relation`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (and (<= (- 3) x 3) (= (abs x) 2)))").model
        val relation = source.factors.filterIsInstance<Abs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(-2L, 2L), solutions)
    }

    @Test
    fun `XCSP absolute value expressions solve their retained source relation`() {
        val source = Xcsp3.parse(
            """<instance><variables><var id="x">-3..3</var></variables>
                <constraints><intension>eq(abs(x),2)</intension></constraints></instance>""",
        ).problem
        val relation = source.factors.filterIsInstance<Abs>().single()

        val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

        assertEquals(setOf(-2L, 2L), solutions)
    }

    @Test
    fun `absolute value outputs follow moves through maintained definitions`() {
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
}
