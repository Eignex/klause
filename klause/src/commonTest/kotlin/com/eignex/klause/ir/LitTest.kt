package com.eignex.klause.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LitTest {

    @Test
    fun `make should encode variable and polarity`() {
        for (v in listOf(0, 1, 7, 31, 1000, Int.MAX_VALUE ushr 1)) {
            val pos = Lit.make(v, positive = true)
            assertEquals(v, Lit.variable(pos))
            assertTrue(Lit.isPositive(pos))

            val neg = Lit.make(v, positive = false)
            assertEquals(v, Lit.variable(neg))
            assertFalse(Lit.isPositive(neg))
        }
    }

    @Test
    fun `negate should flip polarity and keep the variable`() {
        for (v in 0..5) {
            val pos = Lit.make(v, positive = true)
            val flipped = Lit.negate(pos)
            assertEquals(v, Lit.variable(flipped))
            assertFalse(Lit.isPositive(flipped))

            assertEquals(pos, Lit.negate(flipped))
        }
    }

    @Test
    fun `evaluate should follow the literal polarity`() {
        val pos = Lit.make(0, positive = true)
        val neg = Lit.make(0, positive = false)

        assertTrue(Lit.evaluate(pos, value = true))
        assertFalse(Lit.evaluate(pos, value = false))

        assertFalse(Lit.evaluate(neg, value = true))
        assertTrue(Lit.evaluate(neg, value = false))
    }
}
