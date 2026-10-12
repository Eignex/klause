package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtLibProductsTest {
    @Test
    fun `bounded integer products use the shared product factor`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 6)))",
        )

        assertTrue(parsed.model.factors.any { it is Product })
    }

    @Test
    fun `bounded integer real products use the shared mixed product factor`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Real) " +
                "(assert (and (<= 1 x 3) (= (* (to_real x) y) 6)))",
        )

        assertTrue(parsed.model.factors.any { it is RealProduct })
    }

    @Test
    fun `real products use the shared continuous product factor`() {
        val parsed = SmtLib.parse("(declare-const x Real) (assert (= (* x x) 2))")

        assertTrue(parsed.model.factors.any { it is ContinuousProduct })
    }

    @Test
    fun `bounded powers share squared intermediates`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (and (<= 0 x 2) (= (^ x 4) 16)))")

        assertTrue(parsed.model.factors.count { it is Product } <= 3)
    }

    @Test
    fun `word overflowing and open products decline finite lowering`() {
        val sources = listOf(
            "(declare-const x Int) (assert (= (* x x) 4))",
            "(declare-const x Int) (assert (and (<= 3037000500 x 3037000501) (= (* x x) 1)))",
            "(declare-const x Int) (declare-const y Real) (assert (= (* (to_real x) y) 1))",
        )

        for (source in sources) assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }
}
