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
    fun `parallel binding values keep the enclosing symbol scope`() {
        val commands = SExprReader("""
            (declare-const x Int)
            (assert (let ((x (! x :named global))) (= x global)))
        """.trimIndent()).readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(define-fun global () Int x)", result.commands[1].smtText())
    }

    @Test
    fun `leaving a shadowed binder preserves the enclosing binding`() {
        val commands = SExprReader("""
            (declare-const x Int)
            (assert (let ((x 1)) (= (let ((x 2)) x) (! x :named captured))))
        """.trimIndent()).readAll()

        assertFailsWith<UnsupportedSmtException> { captureSmtSource(commands, ExecutionControl.Never) }
    }

    @Test
    fun `numeric symbol binders cannot capture literal tokens in named terms`() {
        val commands = SExprReader("(assert (let ((|1| 9)) (= (! 1 :named one) 1)))").readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(define-fun one () Int 1)", result.commands.first().smtText())
    }

    @Test
    fun `qualified names and sort syntax cannot capture term binders`() {
        val commands = SExprReader("""
            (declare-const x Int)
            (assert (let ((Int 9) (|as| 8) (x 7)) (= (! (as x Int) :named label) x)))
        """.trimIndent()).readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(define-fun label () Int (as x Int))", result.commands[1].smtText())
    }

    @Test
    fun `named terms can contain their own closed let bindings`() {
        val commands = SExprReader("(assert (= (! (let ((x 1)) x) :named one) one))").readAll()

        val result = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals("(define-fun one () Int (let ((x 1)) x))", result.commands.first().smtText())
    }

    @Test
    fun `closed named terms support deep lexical binding chains`() {
        val depth = 10_000
        var body: SExpr = SExpr.Atom("b${depth - 1}")
        for (index in depth - 1 downTo 0) {
            val value = if (index == 0) "true" else "b${index - 1}"
            val binding = SExpr.SList(listOf(SExpr.Atom("b$index"), SExpr.Atom(value)))
            body = SExpr.SList(listOf(SExpr.Atom("let"), SExpr.SList(listOf(binding)), body))
        }
        val annotation = SExpr.SList(listOf(SExpr.Atom("!"), body, SExpr.Atom(":named"), SExpr.Atom("label")))
        val commands = listOf(SExpr.SList(listOf(SExpr.Atom("assert"), annotation)))

        val result = normalizeSmtSourceCommands(commands, SmtLibScope(ExecutionControl.Never), ExecutionControl.Never)

        assertEquals(2, result.size)
        val definition = result.first() as SExpr.SList
        assertEquals(SExpr.Atom("define-fun"), definition.items[0])
        assertEquals(SExpr.Atom("label"), definition.items[1])
        assertEquals(SExpr.Atom("Bool"), definition.items[3])
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
