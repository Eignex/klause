package com.eignex.klause.formats.minizinc

import kotlin.test.Test
import kotlin.test.assertFailsWith

class OznEvalRobustnessTest {

    private fun render(src: String): String =
        OznEvaluator(OznParser(OznLexer(src).tokenize()).parse()).render(emptyMap())

    @Test
    fun `comprehension generator variables do not leak into later output`() {
        // `i` is scoped to the comprehension; a later reference must be unresolved, not the stale
        // last loop value.
        assertFailsWith<OznEvalException> { render("output [show([i | i in 1..3]), show(i)];") }
    }

    @Test
    fun `integer division by zero is an eval error not a raw arithmetic exception`() {
        assertFailsWith<OznEvalException> { render("output [show(4 div 0)];") }
        assertFailsWith<OznEvalException> { render("output [show(4 mod 0)];") }
    }

    @Test
    fun `an over-range integer literal is a parse error not a raw number-format exception`() {
        assertFailsWith<OznParseException> { render("output [show(99999999999999999999)];") }
    }
}
