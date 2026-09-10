package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.minizinc.OznLexer
import com.eignex.klause.formats.minizinc.OznParser
import kotlin.test.Test
import kotlin.test.assertTrue

class OznEvaluatorTest {

    private fun render(src: String): String =
        OznEvaluator(OznParser(OznLexer(src).tokenize()).parse()).render(emptyMap())

    @Test
    fun `slash renders float division not integer division`() {
        val out = render("output [show(3 / 2)];")
        assertTrue("1.5" in out, out)
    }

    @Test
    fun `range binds looser than arithmetic in a generator source`() {
        val out = render("output [show([i | i in 1..1+2])];")
        assertTrue("[1, 2, 3]" in out, out)
    }

    @Test
    fun `conjunction binds tighter than disjunction`() {
        val out = render("output [show(true \\/ false /\\ false)];")
        assertTrue("true" in out, out)
    }

    @Test
    fun `a multi-name array comprehension is a cartesian product`() {
        val out = render("output [show([i + j | i, j in [10, 20]])];")
        assertTrue("[20, 30, 30, 40]" in out, out)
    }
}
