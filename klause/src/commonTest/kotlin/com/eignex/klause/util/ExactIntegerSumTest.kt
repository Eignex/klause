package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ExactIntegerSumTest {
    @Test
    fun `opposing deltas cancel when their exact difference exceeds Long range`() {
        val sum = ExactIntegerSum()
        sum.addDelta(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE)

        sum.addDelta(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE)

        assertEquals(0.0, sum.toDouble())
    }
}
