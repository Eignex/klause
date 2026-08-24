package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A `let`-bound term that an assertion forces true should be posted, not reified.
 *
 * Open operands are posted as strict-order theory alternatives, two reified atoms per pair.
 */
class SmtLibDistinctLetTest {

    private fun boolVarsFor(operands: Int): Int {
        val names = (0 until operands).joinToString(" ") { "x$it" }
        val decls = (0 until operands).joinToString("\n") { "(declare-fun x$it () Int)" }
        val text = "(set-logic QF_LIA)\n$decls\n(assert (let ((b (distinct $names))) b))\n(check-sat)"
        return SmtLib.parse(text).model.numBoolVars
    }

    @Test
    fun `a let-bound open distinct posts two strict-order atoms per pair`() {
        assertEquals(1 + 40 * 39, boolVarsFor(40))
    }

    @Test
    fun `widening a let-bound open distinct grows quadratically`() {
        assertEquals(1 + 80 * 79, boolVarsFor(80))
    }
}
