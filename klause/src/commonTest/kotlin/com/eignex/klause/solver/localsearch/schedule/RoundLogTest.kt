package com.eignex.klause.solver.localsearch.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [RoundLog] / [RoundAccumulator] statistics: counts, acceptance ratio, Welford moments. */
class RoundLogTest {

    @Test
    fun `acceptance ratio is accepted over proposed and zero when nothing proposed`() {
        assertEquals(0.75, RoundLog(4, 3, 0.0, 0.0, 0.0, 1.0).acceptanceRatio, 1e-9)
        assertEquals(0.0, RoundLog(0, 0, 0.0, 0.0, 0.0, 1.0).acceptanceRatio, 1e-9)
    }

    @Test
    fun `accumulator counts proposed and accepted moves`() {
        val acc = RoundAccumulator()
        acc.record(costDelta = -1.0, accepted = true)
        acc.record(costDelta = 2.0, accepted = false)
        acc.record(costDelta = 0.5, accepted = true)
        assertEquals(3, acc.proposed)
        assertEquals(2, acc.accepted)
        val log = acc.snapshot(temperature = 5.0)
        assertEquals(3, log.proposed)
        assertEquals(2, log.accepted)
        assertEquals(2.0 / 3.0, log.acceptanceRatio, 1e-9)
        assertEquals(5.0, log.temperature, 1e-9)
    }

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
    fun `a single sample has zero variance`() {
        val acc = RoundAccumulator()
        acc.record(3.0, accepted = true)
        val log = acc.snapshot(temperature = 1.0)
        assertEquals(3.0, log.costMean, 1e-9)
        assertEquals(0.0, log.costVariance, 1e-9)
    }

    @Test
    fun `an empty round reports zeroed statistics`() {
        val log = RoundAccumulator().snapshot(temperature = 2.0)
        assertEquals(0, log.proposed)
        assertEquals(0.0, log.costMean, 1e-9)
        assertEquals(0.0, log.costVariance, 1e-9)
        assertEquals(0.0, log.bestCost, 1e-9)
    }

    @Test
    fun `incumbent cost is the latest observed and step is carried through`() {
        val acc = RoundAccumulator()
        acc.observeCost(10.0)
        acc.observeCost(3.0)
        acc.observeCost(7.0)
        val log = acc.snapshot(temperature = 1.0, step = 42L)
        assertEquals(3.0, log.bestCost, 1e-9)
        assertEquals(7.0, log.incumbentCost, 1e-9)
        assertEquals(42L, log.step)
    }

    @Test
    fun `incumbent cost falls back to best when no cost was observed`() {
        val log = RoundAccumulator().snapshot(temperature = 1.0)
        assertEquals(0.0, log.incumbentCost, 1e-9)
        assertEquals(0L, log.step)
    }

    @Test
    fun `best cost tracks the minimum observed`() {
        val acc = RoundAccumulator()
        acc.observeCost(10.0)
        acc.observeCost(3.0)
        acc.observeCost(7.0)
        assertEquals(3.0, acc.snapshot(temperature = 1.0).bestCost, 1e-9)
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
