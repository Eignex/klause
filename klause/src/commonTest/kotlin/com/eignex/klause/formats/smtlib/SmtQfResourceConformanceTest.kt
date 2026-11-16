package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SmtQfResourceConformanceTest {
    @Test
    fun `deep array sorts retain source identities during typed capture`() {
        val sort = "(Array Bool ".repeat(4096) + "Bool" + ")".repeat(4096)
        val commands = SExprReader("(declare-const a $sort) (assert (= a a))").readAll()

        val first = captureSmtSource(commands, ExecutionControl.Never)
        val second = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals(sort, first.declarations.single().result.toSmtLib())
        assertEquals(first.declarations.single().result, second.declarations.single().result)
        assertEquals(first.declarations.single().result.hashCode(), second.declarations.single().result.hashCode())
        assertEquals(1, first.original!!.assertions.size)
    }

    @Test
    fun `deep applied free sorts retain source identities during typed capture`() {
        val sort = "(F ".repeat(4096) + "Bool" + ")".repeat(4096)
        val commands = SExprReader("(declare-sort F 1) (declare-const a $sort) (assert (= a a))").readAll()

        val first = captureSmtSource(commands, ExecutionControl.Never)
        val second = captureSmtSource(commands, ExecutionControl.Never)

        assertEquals(sort, first.declarations.single().result.toSmtLib())
        assertEquals(first.declarations.single().result, second.declarations.single().result)
        assertEquals(first.declarations.single().result.hashCode(), second.declarations.single().result.hashCode())
        assertEquals(1, first.original!!.assertions.size)
    }

    @Test
    fun `deep structural interning observes the current remaining allowance`() {
        val sort = "(Array Bool ".repeat(4096) + "Bool" + ")".repeat(4096)
        var work = 0L
        var limit = Long.MAX_VALUE
        val control = ExecutionControl(Cancellation { work >= limit }, WorkMeter { work += it })
        val scope = SmtLibScope(control)
        scope.command(SExprReader("(declare-const a $sort)").readAll().single())
        limit = work + 100

        assertFailsWith<PolynomialSourceInterrupted> {
            scope.command(SExprReader("(assert (= a a))").readAll().single())
        }
    }

    @Test
    fun `sort structural keys observe borrowed argument list changes`() {
        val arguments = mutableListOf<SmtSort>(SmtSort.Int)
        val borrowed = SmtSort.Array(SmtSort.Bool, SmtSort.Uninterpreted("F", arguments))
        val expected = SmtSort.Array(SmtSort.Bool, SmtSort.Uninterpreted("F", listOf(SmtSort.Real)))
        borrowed.hashCode()
        assertNotEquals(expected, borrowed)

        arguments[0] = SmtSort.Real

        assertEquals(expected, borrowed)
        assertEquals(expected.hashCode(), borrowed.hashCode())
    }
}
