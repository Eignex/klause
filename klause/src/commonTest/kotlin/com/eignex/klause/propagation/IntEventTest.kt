package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertContentEquals

class IntEventTest {
    @Test
    fun `small bound subscriptions should keep lower and upper event order`() {
        val cases = listOf(
            intArrayOf() to intArrayOf(),
            intArrayOf(7) to intArrayOf(IntEvent.pack(7, IntEvent.LB_RAISED), IntEvent.pack(7, IntEvent.UB_LOWERED)),
        )
        for ((variables, expected) in cases) {
            val watches = IntEvent.boundEventWatches(variables)

            assertContentEquals(expected, watches)
        }
    }
}
