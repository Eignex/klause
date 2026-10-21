package com.eignex.klause.lp

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals

class ExactFactorComparisonTest {
    @Test
    fun `integral Boolean folding should preserve literal polarity and complements`() {
        val terms = mapOf(0 to BigFraction.ofLong(2).reciprocal())
        val form = ExactRowForm(terms, BigFraction.ofLong(5),
            intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(0, false)),
            listOf(3L, -4L, 2L).map { BigFraction.ofLong(it) }, LinearOp.LE, strict = false, hasReals = true)
        for (first in listOf(false, true)) for (second in listOf(false, true)) {
            for (truth in listOf(false, true)) {
                val expected = 5L - (if (first) 3L else 0L) + (if (!second) 4L else 0L) -
                    (if (!first) 2L else 0L)

                val comparison = form.comparison(truth) { if (it == 0) first else second }

                assertEquals(BigFraction.ofLong(expected), comparison.bound)
                assertEquals(if (truth) LinearOp.LE else LinearOp.GE, comparison.op)
                assertEquals(!truth, comparison.strict)
                assertEquals(terms, comparison.terms)
            }
        }
    }

    @Test
    fun `Boolean folding should preserve callback order`() {
        val form = ExactRowForm(emptyMap(), BigFraction.ofLong(2),
            intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, true)),
            listOf(0L, 2L, 3L).map { BigFraction.ofLong(it) }, LinearOp.LE, strict = false, hasReals = false)
        val visited = ArrayList<Int>()

        form.comparison(true) {
            visited += it
            false
        }

        assertEquals(listOf(0, 0, 1), visited)
    }

    @Test
    fun `integer Boolean folding should retain exact overflowing endpoints`() {
        for (rhs in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val form = ExactRowForm(emptyMap(), BigFraction.ofLong(rhs),
                intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), LinearOp.LE, strict = false, hasReals = false)
            for (first in listOf(false, true)) for (second in listOf(false, true)) {
                val expected = bigIntOf(rhs) - bigIntOf(if (first) 1L else 0L) +
                    bigIntOf(if (second) 1L else 0L)

                val comparison = form.comparison(true) { if (it == 0) first else second }

                assertEquals(BigFraction.of(expected, BIG_ONE), comparison.bound)
            }
        }
    }

    @Test
    fun `nonrepresentable narrow Boolean inputs should retain exact values`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val wide = BigFraction.of(BIG_ONE shl 80, BIG_ONE)
        for ((rhs, coefficient) in listOf(BigFraction.ZERO to half, BigFraction.ZERO to wide,
            half to BigFraction.ONE)) {
            val form = ExactRowForm(emptyMap(), rhs, intArrayOf(Lit.make(0, true)),
                listOf(coefficient), LinearOp.LE, strict = false, hasReals = false)
            for (truth in listOf(false, true)) {
                val comparison = form.comparison(true) { truth }

                assertEquals(if (truth) rhs - coefficient else rhs, comparison.bound)
            }
        }
    }
}
