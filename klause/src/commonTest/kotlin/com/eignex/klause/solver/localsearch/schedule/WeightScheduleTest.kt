package com.eignex.klause.solver.localsearch.schedule

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared violation-weight schedule (#721): parity with the Cbls/FJ formulas + stall cadence. */
class WeightScheduleTest {

    @Test
    fun `feasibility-jump config reproduces decay-then-bump bit for bit`() {
        val decay = 0.9
        val inc = 2.0
        val w = doubleArrayOf(5.0, 1.0, 3.0)
        val base = doubleArrayOf(1.0, 1.0, 1.0)
        val violated = intArrayOf(0, 2)
        // The exact arithmetic FeasibilityJump used inline: decay every weight toward base, then bump.
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
        // Cbls inline: bump first, then w ← (1-smoothFactor)·w + smoothFactor·baseWeight·base.
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
    fun `cbls with smoothing disabled only bumps and never draws the rng`() {
        val ws = WeightSchedule.cbls(smoothProb = 0.0, smoothFactor = 0.8, baseWeight = 1.0, stallIncrement = 3.0)
        val w = doubleArrayOf(10.0)
        val base = doubleArrayOf(1.0)
        val probe = Random(7)
        val reference = Random(7)
        ws.bumpAndRelax(w, base, intArrayOf(0), probe)
        assertEquals(13.0, w[0], 1e-12) // pure bump, no pull toward base
        // smoothProb 0 must not consume the rng stream.
        assertEquals(reference.nextLong(), probe.nextLong())
    }

    @Test
    fun `feasibility-jump relax does not draw the rng`() {
        // relaxProbability == 1.0 short-circuits before any rng draw, preserving FJ's tie-break stream.
        val ws = WeightSchedule.feasibilityJump(weightDecay = 0.9)
        val probe = Random(7)
        val reference = Random(7)
        ws.bumpAndRelax(doubleArrayOf(5.0), doubleArrayOf(1.0), intArrayOf(0), probe)
        assertEquals(reference.nextLong(), probe.nextLong())
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
    fun `an improving step resets the stall window`() {
        val ws = WeightSchedule(bumpAfter = 2, increment = 1.0)
        val w = doubleArrayOf(0.0)
        val base = doubleArrayOf(0.0)
        val rng = Random(0)
        ws.maintain(0L, 10L, w, base, intArrayOf(0), rng)
        ws.maintain(1L, 10L, w, base, intArrayOf(0), rng)
        ws.maintain(2L, 5L, w, base, intArrayOf(0), rng) // improvement → resets window
        ws.maintain(3L, 5L, w, base, intArrayOf(0), rng)
        assertEquals(0.0, w[0], 1e-12) // only 1 stalled step since the improvement
        ws.maintain(4L, 5L, w, base, intArrayOf(0), rng)
        assertEquals(1.0, w[0], 1e-12)
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

    @Test
    fun `monotone escalation never relaxes when keep is one`() {
        val ws = WeightSchedule(bumpAfter = 1, increment = 1.0, relaxKeep = 1.0)
        val w = doubleArrayOf(0.0)
        val base = doubleArrayOf(0.0)
        ws.bumpAndRelax(w, base, intArrayOf(0), Random(0))
        ws.bumpAndRelax(w, base, intArrayOf(0), Random(0))
        assertEquals(2.0, w[0], 1e-12)
    }
}
