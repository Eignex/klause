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
}
