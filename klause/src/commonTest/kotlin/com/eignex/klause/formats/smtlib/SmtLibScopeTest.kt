package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.SourceExecutionKind
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.executionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SmtLibScopeTest {
    @Test
    fun `unused helper result sorts should be checked independently of logic declarations`() {
        for (logic in listOf("", "(set-logic QF_LIA)", "(set-logic QF_NRA)", "(set-logic ALL)")) {
            val source = "$logic (define-fun invalid ((n Int)) Bool (+ n 1)) (assert true)"

            assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
        }
    }

    @Test
    fun `unused helper bodies should enforce the declared argument sorts of called helpers`() {
        val source = "(define-fun accepts ((n Int)) Bool true) " +
            "(define-fun invalid () Bool (accepts false)) (assert true)"

        assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }

    @Test
    fun `nonlinear helper applications should preserve original finite source values`() {
        for (body in listOf("(* v v)", "(^ v 2)")) {
            val source = SmtLib.parse("(set-logic QF_NIA) (declare-const n Int) " +
                "(define-fun square ((v Int)) Int $body) (assert (<= 1 n 2)) (assert (= (square n) 4))")

            val result = BacktrackSolver.source(source.model).solve()

            assertEquals("2", assertIs<OpenTheoryResult.Sat>(result).assignment
                .intValue(source.intVarNames.getValue("n")))
        }
    }

    @Test
    fun `unused helper declarations should retain unrelated native products`() {
        val source = SmtLib.parse("(declare-const n Int) (define-fun identity ((v Int)) Int v) " +
            "(assert (<= 1 n 2)) (assert (= (* n n) 4))")

        val result = BacktrackSolver.source(source.model).solve()

        assertEquals("2", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(source.intVarNames.getValue("n")))
    }

    @Test
    fun `unrestricted integer declarations beside retained real products should reach shared setup`() {
        val source = SmtLib.parse("(declare-const unused Int) (declare-const x Real) " +
            "(assert (= x 1)) (assert (= (* x x) 1))")
        val invocation = SourceSetup.prepare(SourceSetupRequest(source.source,
            ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK))

        val result = BacktrackSolver.source(source.model).solve()

        assertEquals(SourceExecutionKind.NONLINEAR_INTEGER, SourceSetup.executionKind(invocation))
        assertEquals("1", assertIs<OpenTheoryResult.Sat>(result).assignment
            .realValue(source.realVarNames.getValue("x")))
    }

    @Test
    fun `declared signed integer symbols should retain their values in real contexts`() {
        val source = SmtLib.parse("(declare-const -1 Int) (assert (= -1 2)) (assert (= (to_real -1) 2.0))")

        val result = BacktrackSolver.source(source.model).solve()

        assertEquals("2", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(source.intVarNames.getValue("-1")))
    }

    @Test
    fun `declared signed decimal symbols should retain their integer sorts in helper arguments`() {
        val source = SmtLib.parse("(declare-const -1.5 Int) (define-fun accepts ((n Int)) Bool (= n 3)) " +
            "(assert (accepts -1.5))")

        val result = BacktrackSolver.source(source.model).solve()

        assertEquals("3", assertIs<OpenTheoryResult.Sat>(result).assignment
            .intValue(source.intVarNames.getValue("-1.5")))
    }

    @Test
    fun `quoted and bare signed symbol references should share their declared identity`() {
        val source = SmtLib.parse("(declare-const |-1| Int) (assert (= -1 2)) (assert (= |-1| 2))")

        val result = BacktrackSolver.source(source.model).solve()

        assertEquals(mapOf("|-1|" to 0), source.intVarNames)
        assertEquals("2", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
    }

    @Test
    fun `quoted numeral symbols should remain separate from numeral literals`() {
        val source = SmtLib.parse("(declare-const |0| Int) (assert (= |0| 2)) (assert (= 0 0))")

        val result = BacktrackSolver.source(source.model).solve()

        assertEquals(mapOf("|0|" to 0), source.intVarNames)
        assertEquals("2", assertIs<OpenTheoryResult.Sat>(result).assignment.intValue(0))
    }

    @Test
    fun `linear sources should enforce the same nonincremental query boundary`() {
        for (suffix in listOf("(assert false)", "(check-sat)", "(declare-const later Int)")) {
            val source = "(declare-const x Int) (assert (= x 1)) (check-sat) $suffix"

            assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
        }
    }

    @Test
    fun `native Boolean admission should accept a logic whose source operators are supported`() {
        val source = SmtLibPolynomial.parse("(set-logic QF_UF) (declare-const b Bool) (assert b)")

        assertEquals(mapOf("b" to 0), source.boolVarNames)
    }
}
