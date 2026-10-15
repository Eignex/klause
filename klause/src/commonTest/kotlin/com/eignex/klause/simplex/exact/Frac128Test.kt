package com.eignex.klause.simplex.exact

import kotlin.test.Test
import kotlin.test.assertTrue

class Frac128Test {

    @Test
    fun `fixed-width level should latch overflow past 128 bits`() {
        val ops = Frac128Ops()
        val big = ops.ofLong(1L shl 62)
        val sq = ops.times(big, big)
        assertTrue(!ops.overflowed())
        ops.times(sq, big)
        assertTrue(ops.overflowed())
    }
}
