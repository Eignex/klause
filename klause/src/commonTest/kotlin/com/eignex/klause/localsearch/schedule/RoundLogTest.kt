package com.eignex.klause.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [RoundLog] / [RoundAccumulator] statistics: counts, acceptance ratio, Welford moments. */
class RoundLogTest {

    @Test
    fun `accumulator computes mean and population variance of the cost deltas`() {
        val acc = RoundAccumulator()
        // deltas 2, 4, 4, 4, 5, 5, 7, 9: mean 5, population variance 4.
        for (d in listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)) acc.record(d, accepted = true)
        val log = acc.snapshot(temperature = 1.0)
        assertEquals(5.0, log.costMean, 1e-9)
        assertEquals(4.0, log.costVariance, 1e-9)
    }

    @Test
    fun `clear resets the accumulator for the next round`() {
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
    fun `round log rejects inconsistent counts`() {
        assertFailsWith<IllegalArgumentException> { RoundLog(2, 3, 0.0, 0.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { RoundLog(-1, 0, 0.0, 0.0, 0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { RoundLog(2, 1, 0.0, -1.0, 0.0, 1.0) }
    }

    @Test
    fun `variance stays accurate over a long round`() {
        val acc = RoundAccumulator()
        // Alternating 0 and 100 over many samples: mean 50, population variance 2500.
        repeat(10_000) { acc.record(if (it % 2 == 0) 0.0 else 100.0, accepted = it % 2 == 0) }
        val log = acc.snapshot(temperature = 1.0)
        assertEquals(50.0, log.costMean, 1e-6)
        assertEquals(2500.0, log.costVariance, 1e-3)
        assertTrue(log.costVariance > 0.0)
    }
}
