package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class SmtQfResourceConformanceTest {
    @Test
    fun `deep array sorts retain source identities during typed capture`() {
        val sort = "(Array Bool ".repeat(4096) + "Bool" + ")".repeat(4096)
        val commands = SExprReader("(declare-const a $sort) (assert (= ((as ite $sort) true a a) a))").readAll()

        val first = captureSmtSource(commands, ExecutionControl.Never)
        val second = captureSmtSource(commands, ExecutionControl.Never)
        val exposed = exposeSmtGroundFunctions(first, ExecutionControl.Never)

        assertEquals(sort, first.declarations.single().result.toSmtLib())
        assertEquals(first.declarations.single().result, second.declarations.single().result)
        assertEquals(first.declarations.single().result.hashCode(), second.declarations.single().result.hashCode())
        assertEquals(1, first.original!!.assertions.size)
        assertEquals(2, exposed.commands.size)
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

    @Test
    fun `nested array cardinalities retain their capped finite meaning`() {
        var sort: SmtSort = SmtSort.Bool
        repeat(256) { sort = SmtSort.Array(SmtSort.Bool, sort) }
        val registry = SmtDatatypes(ExecutionControl.Never)
        val cap = parseBigInt("2")
        val cardinalities = SmtDatatypeCardinalities(registry, cap) {}

        val cardinality = cardinalities.of(sort)

        assertEquals(cap, cardinality)
    }
}
