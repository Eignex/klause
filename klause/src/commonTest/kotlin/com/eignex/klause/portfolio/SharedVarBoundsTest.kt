package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared globally-valid variable-bound manager keeps, per variable, the tightest lower and upper
 * bound any arm has published — a monotone intersection. Verified in isolation (a deterministic fold).
 */
class SharedVarBoundsTest {

    @Test
    fun `unset bounds are the open interval`() {
        val vb = SharedVarBounds(numIntVars = 2)
        assertEquals(Long.MIN_VALUE, vb.lowerOf(0))
        assertEquals(Long.MAX_VALUE, vb.lowerOf(0).let { vb.upperOf(0) })
    }

    @Test
    fun `publish keeps the tightest bound each side`() {
        val vb = SharedVarBounds(numIntVars = 1)
        vb.publish(0, lower = 2, upper = 9)
        vb.publish(0, lower = 4, upper = 7) // tighter both sides
        vb.publish(0, lower = 1, upper = 8) // looser both sides — ignored
        assertEquals(4, vb.lowerOf(0))
        assertEquals(7, vb.upperOf(0))
    }

    @Test
    fun `out-of-range variables are ignored`() {
        val vb = SharedVarBounds(numIntVars = 1)
        vb.publish(5, lower = 0, upper = 0) // no such variable
        assertEquals(Long.MIN_VALUE, vb.lowerOf(5))
        assertEquals(Long.MAX_VALUE, vb.upperOf(5))
    }
}
