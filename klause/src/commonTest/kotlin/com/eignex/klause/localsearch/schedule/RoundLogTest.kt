package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundLogTest {

    @Test
    fun `clear should reset the accumulator for the next round`() {
        val acc = RoundAccumulator()
        acc.record(1.0, accepted = true)
        acc.observeCost(1.0)
        acc.clear()
        assertEquals(0, acc.proposed)
        assertEquals(0, acc.accepted)
        val log = acc.snapshot(temperature = 1.0)
        assertEquals(0.0, log.costMean, 1e-9)
        assertEquals(0.0, log.bestCost, 1e-9)
    }

    @Test
    fun `variance should stay accurate over a long round`() {
        val acc = RoundAccumulator()
        // Alternating 0 and 100 over many samples: mean 50, population variance 2500.
        repeat(10_000) { acc.record(if (it % 2 == 0) 0.0 else 100.0, accepted = it % 2 == 0) }
        val log = acc.snapshot(temperature = 1.0)
        assertEquals(50.0, log.costMean, 1e-6)
        assertEquals(2500.0, log.costVariance, 1e-3)
        assertTrue(log.costVariance > 0.0)
    }
}
