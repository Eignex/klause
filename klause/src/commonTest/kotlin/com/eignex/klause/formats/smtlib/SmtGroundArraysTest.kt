package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtGroundArraysTest {
    @Test
    fun `store should replace the selected value at its index`() {
        val source = source("(declare-const a (Array Bool Bool)) " +
            "(assert (not (select (store a true true) true)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `store should retain values at other indexes`() {
        val source = source("(declare-const a (Array Bool Bool)) (assert (select a false)) " +
            "(assert (not (select (store a true false) false)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `equal arrays should agree at every represented index`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(assert (= a b)) (assert (distinct (select a false) (select b false)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array equality under negation should require a differing read`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(assert (= (select a false) (select b false))) " +
            "(assert (= (select a true) (select b true))) (assert (not (= a b)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `disequal arrays without source reads should reconstruct total witnesses`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(assert (not (= a b)))")
        val assignment = assertIs<OpenTheoryResult.Sat>(BacktrackSolver.source(source.model).solve()).assignment

        val model = source.reconstructModel(assignment::boolValue, assignment::intValue, assignment::realValue)

        val a = assertIs<SmtValue.Array>(model.values[0].second)
        val b = assertIs<SmtValue.Array>(model.values[1].second)
        assertTrue(a.entries.zip(b.entries).any { it.first.second != it.second.second })
        assertTrue(model.toSmtLib().contains("(as const (Array Bool Bool))"))
        assertEquals(listOf("a", "b"), model.values.map { it.first.name })
    }

    @Test
    fun `array conditionals should retain their selected branch`() {
        val source = source("(declare-const a (Array Bool Bool)) (declare-const b (Array Bool Bool)) " +
            "(declare-const c Bool) (assert c) (assert (select a true)) " +
            "(assert (not (select (ite c a b) true)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array elements should retain nested store reads`() {
        val source = source("(declare-const a (Array Bool (Array Bool Bool))) " +
            "(declare-const b (Array Bool Bool)) (assert (select b false)) " +
            "(assert (not (select (select (store a true b) true) false)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `array indexes should respect extensional equality`() {
        val source = source("(declare-const a (Array (Array Bool Bool) Bool)) " +
            "(declare-const i (Array Bool Bool)) (declare-const j (Array Bool Bool)) " +
            "(assert (= i j)) (assert (select a i)) (assert (not (select a j)))")

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `cancellation should interrupt array closure before publication`() {
        val input = captureSmtSource(SExprReader("(declare-const a (Array Bool Bool))").readAll(), Cancellation.Never)

        assertFailsWith<PolynomialSourceInterrupted> {
            reduceSmtGroundArrays(input, Cancellation { true })
        }
    }

    private fun source(script: String): SmtLibProblem {
        val reduction = reduceSmtGroundArrays(captureSmtSource(SExprReader(script).readAll(), Cancellation.Never),
            Cancellation.Never)
        return lowerSmtReduction(reduction, Long.MIN_VALUE, Long.MAX_VALUE, ProblemSettings(), Cancellation.Never,
            smtLibSemantics.resolve())
    }
}
