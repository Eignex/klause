package com.eignex.klause.formats.smtlib

import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtAnnotationsTest {
    @Test
    fun `named annotations define closed terms in postorder`() {
        val commands = SExprReader("""
            (declare-const x Int)
            (assert (= (! (+ (! x :named inner) 1) :named outer) (+ inner 1)))
            (assert (= outer (+ x 1)))
        """.trimIndent()).readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals(listOf(
            "(declare-const x Int)",
            "(define-fun inner () Int x)",
            "(define-fun outer () Int (+ x 1))",
            "(assert (= (+ x 1) (+ inner 1)))",
            "(assert (= outer (+ x 1)))",
        ), result.commands.map { it.smtText() })
    }

    @Test
    fun `named annotations reject references to enclosing binders`() {
        val commands = SExprReader("(declare-const x Int) (assert (let ((x 1)) (= (! x :named local) 1)))").readAll()

        assertFailsWith<UnsupportedSmtException> { captureSmtSource(commands, ExecutionControl.Never) }
    }

    @Test
    fun `named terms can contain their own closed let bindings`() {
        val commands = SExprReader("(assert (= (! (let ((x 1)) x) :named one) one))").readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(define-fun one () Int (let ((x 1)) x))", result.commands.first().smtText())
    }

    @Test
    fun `metadata annotations do not change the asserted term`() {
        val commands = SExprReader("(assert (! true :weight 3 :custom (a b) :marker))").readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(assert true)", result.commands.single().smtText())
    }

    @Test
    fun `define const is a nullary function definition`() {
        val commands = SExprReader("(define-const one Int 1) (assert (= one 1))").readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(define-fun one () Int 1)", result.commands.first().smtText())
    }

    @Test
    fun `single source assumptions accept arithmetic formulas`() {
        val result = SmtLib.parse("(declare-const x Int) (check-sat-assuming ((> x 2) (< x 5)))")

        assertTrue("x" in result.intVarNames)
    }
}
