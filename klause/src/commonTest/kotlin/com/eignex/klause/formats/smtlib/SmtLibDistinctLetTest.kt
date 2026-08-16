package com.eignex.klause.formats.smtlib

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `let`-bound term that an assertion forces true should be posted, not reified.
 *
 * Reifying costs an auxiliary literal per pairwise disequality, so the difference is quadratic in the
 * operand count — the shape that made a real instance unrepresentable rather than merely slow.
 */
class SmtLibDistinctLetTest {

    private fun boolVarsFor(operands: Int): Int {
        val names = (0 until operands).joinToString(" ") { "x$it" }
        val decls = (0 until operands).joinToString("\n") { "(declare-fun x$it () Int)" }
        val text = "(set-logic QF_LIA)\n$decls\n(assert (let ((b (distinct $names))) b))\n(check-sat)"
        return SmtLib.parse(text).problem.numBoolVars
    }

    @Test
    fun `a let-bound distinct an assertion forces true costs no auxiliary literals`() {
        // Reified, this is n(n-1)/2 fresh literals; posted, it is none.
        assertTrue(boolVarsFor(40) <= 2, "expected no pairwise reification, got ${boolVarsFor(40)} bool vars")
    }

    @Test
    fun `widening a let-bound distinct does not grow the literal count`() {
        assertTrue(boolVarsFor(80) <= boolVarsFor(20) + 2, "literal count grew with the operand count")
    }
}
