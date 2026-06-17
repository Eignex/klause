package com.eignex.klause.solver.localsearch.schedule

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The schedule-axis bundle (#721): one round must drive every adaptive member. */
class ScheduleBundleTest {

    private class SpyPolicy : AdaptivePolicy {
        var observed = 0
        var resets = 0
        override fun observe(round: RoundLog) {
            observed++
        }
        override fun reset() {
            resets++
        }
    }

    private fun round(incumbent: Double, step: Long, accepted: Int) = RoundLog(
        proposed = 10,
        accepted = accepted,
        costMean = 0.0,
        costVariance = 0.0,
        bestCost = incumbent,
        temperature = 1.0,
        incumbentCost = incumbent,
        step = step,
    )

    @Test
    fun `one round drives every adaptive member`() {
        val temperature = AdaptiveCooling(initialRate = 0.95, minRate = 0.5, maxRate = 0.999, adjustStep = 0.2)
        val noise = SpyPolicy()
        val bundle = ScheduleBundle(temperature = temperature, noise = noise)
        // High acceptance (0.9) above target (0.4) → AdaptiveCooling lowers its rate.
        bundle.observe(round(incumbent = 5.0, step = 3L, accepted = 9))
        assertTrue(temperature.coolingRate < 0.95, "temperature schedule did not retune: ${temperature.coolingRate}")
        assertEquals(1, noise.observed)
    }

    @Test
    fun `weight schedule participates in the round`() {
        val weights = WeightSchedule(bumpAfter = 1, increment = 1.0)
        val bundle = ScheduleBundle(weights = weights)
        // Seed an improving round, then a stalled round arms a bump applied to the weights.
        bundle.observe(round(incumbent = 10.0, step = 0L, accepted = 1))
        bundle.observe(round(incumbent = 10.0, step = 1L, accepted = 0))
        val w = doubleArrayOf(0.0)
        weights.applyTo(w, doubleArrayOf(0.0), intArrayOf(0), Random(0))
        assertEquals(1.0, w[0], 1e-12)
    }

    @Test
    fun `reset propagates to every adaptive member`() {
        val noise = SpyPolicy()
        val weights = WeightSchedule(bumpAfter = 1)
        val temperature = AdaptiveCooling()
        val bundle = ScheduleBundle(temperature = temperature, weights = weights, noise = noise)
        bundle.reset()
        assertEquals(1, noise.resets)
    }

    @Test
    fun `a bundle with no members is an inert policy`() {
        val bundle = ScheduleBundle()
        bundle.observe(round(incumbent = 1.0, step = 1L, accepted = 1))
        bundle.reset()
        // No members, no effect, no throw.
        assertTrue(bundle.temperature == null && bundle.weights == null && bundle.noise == null)
    }
}
