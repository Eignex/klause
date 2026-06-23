package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared objective lower-bound manager keeps the cross-arm maximum of the bounds arms
 * prove, ignoring non-informative values. Verified in isolation (no executor), since the logic is a
 * deterministic monotone fold.
 */
class SharedObjectiveBoundTest {

    @Test
    fun `current is negative infinity until a bound is published`() {
        assertEquals(Double.NEGATIVE_INFINITY, SharedObjectiveBound().current())
    }

    @Test
    fun `publish keeps the maximum`() {
        val bounds = SharedObjectiveBound()
        bounds.publish(3.0)
        bounds.publish(7.0)
        bounds.publish(5.0) // a weaker bound does not lower the shared maximum
        assertEquals(7.0, bounds.current())
    }

    @Test
    fun `non-finite publications carry no information`() {
        val bounds = SharedObjectiveBound()
        bounds.publish(4.0)
        bounds.publish(Double.NaN)
        bounds.publish(Double.NEGATIVE_INFINITY)
        bounds.publish(Double.POSITIVE_INFINITY)
        assertEquals(4.0, bounds.current(), "only finite bounds update the maximum")
    }
}
