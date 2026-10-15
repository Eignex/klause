package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.IntHandle
import com.eignex.klause.schema.abs
import com.eignex.klause.schema.eq
import com.eignex.skema.SchemaDef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AbsCompilationTest {
    @Test
    fun `existing integer formats retain absolute value and solve the original relation`() {
        val x = IntHandle("x", -3, 3)
        val models = listOf(
            SchemaDef<SchemaEntry>(mapOf("x" to IntSpec(-3, 3), "magnitude" to NamedConstraint(abs(x) eq 2)))
                .compile().problem,
            parseFlatZinc("var -3..3: x; constraint int_abs(x, 2); solve satisfy;").problem,
            SmtLib.parse("(declare-const x Int) (assert (and (<= (- 3) x 3) (= (abs x) 2)))").model,
            Xcsp3.parse(
                """<instance><variables><var id="x">-3..3</var></variables>
                <constraints><intension>eq(abs(x),2)</intension></constraints></instance>"""
            ).problem,
        )
        for (source in models) {
            val relation = source.factors.filterIsInstance<Abs>().single()

            val solutions = BacktrackSolver(source.bake()).enumerate().map { it.ints[relation.operand] }.toSet()

            assertEquals(setOf(-2L, 2L), solutions)
        }
    }

    @Test
    fun `absolute value outputs follow moves through maintained definitions`() {
        val source = parseFlatZinc("var -3..3: x; var 0..3: y; constraint int_abs(x, y); solve satisfy;").problem
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
