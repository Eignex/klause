package com.eignex.klause.localsearch.schedule

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightScheduleTest {

    @Test
    fun `feasibility-jump config reproduces decay-then-bump bit for bit`() {
        val decay = 0.9
        val inc = 2.0
        val w = doubleArrayOf(5.0, 1.0, 3.0)
        val base = doubleArrayOf(1.0, 1.0, 1.0)
        val violated = intArrayOf(0, 2)
        // FeasibilityJump arithmetic: decay every weight toward base, then bump.
        val expected = DoubleArray(w.size) { base[it] + (w[it] - base[it]) * decay }
        expected[0] += inc
        expected[2] += inc

        val ws = WeightSchedule.feasibilityJump(weightBumpAfter = 1, weightIncrement = inc, weightDecay = decay)
        ws.bumpAndRelax(w, base, violated, Random(0))
        for (i in w.indices) assertEquals(expected[i], w[i], "weight $i")
    }

    @Test
    fun `cbls config reproduces bump-then-smooth toward the scaled seed`() {
        val smoothFactor = 0.8
        val baseWeight = 2.0
        val w = doubleArrayOf(5.0, 1.0)
        val base = doubleArrayOf(1.0, 1.0)
        val violated = intArrayOf(0)
        // Cbls: bump first, then w ← (1-smoothFactor)·w + smoothFactor·baseWeight·base.
        val expected = doubleArrayOf(5.0 + 1.0, 1.0).also {
            for (i in it.indices) it[i] = (1.0 - smoothFactor) * it[i] + smoothFactor * baseWeight * base[i]
        }
        val ws = WeightSchedule.cbls(
            stallSteps = 1,
            stallIncrement = 1.0,
            smoothProb = 1.0,
            smoothFactor = smoothFactor,
            baseWeight = baseWeight,
        )
        ws.bumpAndRelax(w, base, violated, Random(0))
        for (i in w.indices) assertEquals(expected[i], w[i], 1e-9, "weight $i")
    }

    @Test
    fun `stall cadence bumps only after the configured number of non-improving steps`() {
        val ws = WeightSchedule(bumpAfter = 3, increment = 1.0) // relaxKeep 1.0 → no relax
        val w = doubleArrayOf(0.0)
        val base = doubleArrayOf(0.0)
        val rng = Random(0)
        ws.maintain(step = 0L, cost = 10L, weights = w, base = base, violated = intArrayOf(0), rng = rng)
        ws.maintain(step = 1L, cost = 10L, weights = w, base = base, violated = intArrayOf(0), rng = rng)
        ws.maintain(step = 2L, cost = 10L, weights = w, base = base, violated = intArrayOf(0), rng = rng)
        assertEquals(0.0, w[0], 1e-12) // 2 stalled steps < bumpAfter
        ws.maintain(step = 3L, cost = 10L, weights = w, base = base, violated = intArrayOf(0), rng = rng)
        assertEquals(1.0, w[0], 1e-12) // 3rd stalled step triggers the bump
    }

    @Test
    fun `integer cost drops take precedence over fractional increases`() {
        val ws = WeightSchedule(bumpAfter = 1)
        val w = doubleArrayOf(1.0)
        val cost = 9007199254740993L
        ws.observeStep(0L, cost, 0.0)

        ws.observeStep(1L, cost - 1, 0.9)
        ws.applyTo(w, doubleArrayOf(1.0), intArrayOf(0), Random(0))

        assertEquals(1.0, w[0])
    }

    @Test
    fun `fractional feedback re-anchors on restart and reset`() {
        for (reset in listOf(false, true)) {
            val ws = WeightSchedule(bumpAfter = 1)
            val w = doubleArrayOf(1.0)
            ws.observeStep(4L, 23L, 0.1)
            if (reset) ws.reset()
            ws.observeStep(0L, 23L, 0.9)

            ws.observeStep(1L, 23L, 0.8)
            ws.applyTo(w, doubleArrayOf(1.0), intArrayOf(0), Random(0))

            assertEquals(1.0, w[0], "reset=$reset")
        }
    }

    @Test
    fun `a rewound step re-anchors the trackers without bumping`() {
        val ws = WeightSchedule(bumpAfter = 1, increment = 1.0)
        val w = doubleArrayOf(0.0)
        val base = doubleArrayOf(0.0)
        val rng = Random(0)
        ws.maintain(0L, 10L, w, base, intArrayOf(0), rng)
        ws.maintain(5L, 10L, w, base, intArrayOf(0), rng) // bumps (stalled)
        val afterBump = w[0]
        assertTrue(afterBump > 0.0)
        // Restart: step rewinds below the last seen step. The trackers re-anchor; no bump this step.
        ws.maintain(0L, 8L, w, base, intArrayOf(0), rng)
        assertEquals(afterBump, w[0], 1e-12)
    }
}
