package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.minizinc.OznLexer
import com.eignex.klause.formats.minizinc.OznParseException
import com.eignex.klause.formats.minizinc.OznParser
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OznEvalRobustnessTest {

    private fun render(src: String): String =
        OznEvaluator(OznParser(OznLexer(src).tokenize()).parse()).render(emptyMap())

    @Test
    fun `comprehension generator variables do not leak into later output`() {
        assertFailsWith<OznParseException> { render("output [show([i | i in 1..3]), show(i)];") }
    }

    @Test
    fun `division by zero is reported as an evaluation error`() {
        for (operator in listOf("div", "mod")) {
            assertFailsWith<OznParseException> { render("output [show(4 $operator 0)];") }
        }
    }

    @Test
    fun `an over-range integer literal is a parse error not a raw number-format exception`() {
        assertFailsWith<OznParseException> { render("output [show(99999999999999999999)];") }
    }
}
