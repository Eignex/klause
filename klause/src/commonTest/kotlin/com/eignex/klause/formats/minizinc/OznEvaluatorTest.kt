package com.eignex.klause.formats.minizinc

import kotlin.test.Test
import kotlin.test.assertTrue

class OznEvaluatorTest {

    private fun render(src: String): String =
        OznEvaluator(OznParser(OznLexer(src).tokenize()).parse()).render(emptyMap())

    @Test
    fun `slash renders float division not integer division`() {
        // MiniZinc `/` is float division; `3 / 2` must render 1.5, not the truncated 1.
        val out = render("output [show(3 / 2)];")
        assertTrue("1.5" in out, out)
    }
}
