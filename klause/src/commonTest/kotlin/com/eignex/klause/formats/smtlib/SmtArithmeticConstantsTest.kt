package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SmtArithmeticConstantsTest {
    @Test
    fun `deep rational negations should be recognized without a source depth limit`() {
        val constants = SmtArithmeticConstants(ExecutionControl.Never)
        val expression = SExprReader("(- ".repeat(20000) + "1" + ")".repeat(20000)).readAll().single()

        val recognized = constants.knownRational(expression)

        assertEquals(BigFraction.ONE, recognized)
    }

    @Test
    fun `deep rational divisions should be recognized without a source depth limit`() {
        val constants = SmtArithmeticConstants(ExecutionControl.Never)
        val expression = SExprReader("(/ ".repeat(20000) + "1" + " 1)".repeat(20000)).readAll().single()

        val recognized = constants.knownRational(expression)

        assertEquals(BigFraction.ONE, recognized)
    }

    @Test
    fun `deep rational recognition should charge and stop through the containing owner`() {
        var charged = 0L
        val control = ExecutionControl(Cancellation { charged >= 64 }, WorkMeter { charged += it })
        val constants = SmtArithmeticConstants(control)
        val expression = SExprReader("(- ".repeat(20000) + "1" + ")".repeat(20000)).readAll().single()

        assertFailsWith<PolynomialSourceInterrupted> { constants.knownRational(expression) }

        assertEquals(64L, charged)
    }
}
