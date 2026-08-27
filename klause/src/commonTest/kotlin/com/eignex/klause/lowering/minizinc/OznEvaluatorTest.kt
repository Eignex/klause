package com.eignex.klause.lowering.minizinc

import com.eignex.klause.formats.minizinc.*
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

    @Test
    fun `range binds looser than arithmetic in a generator source`() {
        // `1..1+2` is `1..3` in MiniZinc (arithmetic binds tighter than `..`), so the comprehension spans
        // 1, 2, 3. A ladder that binds `..` tighter would parse `(1..1)+2`, which is not evaluable.
        val out = render("output [show([i | i in 1..1+2])];")
        assertTrue("[1, 2, 3]" in out, out)
    }

    @Test
    fun `conjunction binds tighter than disjunction`() {
        // `true \/ false /\ false` is `true \/ (false /\ false)` = true in MiniZinc; a flat,
        // left-associative parse would give `(true \/ false) /\ false` = false.
        val out = render("output [show(true \\/ false /\\ false)];")
        assertTrue("true" in out, out)
    }

    @Test
    fun `a multi-name array comprehension is a cartesian product`() {
        // `[i + j | i, j in [10, 20]]` ranges i and j independently: 10+10, 10+20, 20+10, 20+20. A
        // diagonal reading would bind both names to the same element, giving only 20 and 40.
        val out = render("output [show([i + j | i, j in [10, 20]])];")
        assertTrue("[20, 30, 30, 40]" in out, out)
    }
}
