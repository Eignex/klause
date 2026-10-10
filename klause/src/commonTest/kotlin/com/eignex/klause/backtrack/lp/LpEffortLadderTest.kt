package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.bounding.LpEffort
import com.eignex.klause.lp.bounding.LpEffortLadder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #32: the adaptive per-node LP effort ladder. Verified in isolation (no solver), since the controller
 * is deliberately count-based and therefore deterministic. A `top = BOUND` ladder reduces to the
 * two-rung auto-off (#614) it generalizes, so it must reproduce that behaviour — disable a never-pruning
 * LP, re-probe on backoff, shed a relaxation that goes cold, stay enabled while pruning, and stay
 * bounded over many nodes — and a `top = CUTS` ladder must shed the cut rung before the bound.
 */
class LpEffortLadderTest {

    /** Drive `n` LP-eligible nodes, recording [pruned] for each that actually ran; returns run count. */
    private fun LpEffortLadder.drive(n: Int, pruned: (Int) -> Boolean): Int {
        var runs = 0
        for (i in 0 until n) {
            if (shouldRun()) {
                record(pruned(i))
                runs++
            }
        }
        return runs
    }

    @Test
    fun `a never-pruning bound is disabled after the warmup window`() {
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4)
        repeat(4) {
            assertTrue(c.shouldRun())
            c.record(false)
        }
        assertEquals(LpEffort.OFF, c.rung, "four non-pruning passes over the window must disable the LP")
    }

    @Test
    fun `a re-probe that prunes promotes the ladder back up`() {
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, reprobeBase = 4)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        repeat(3) { assertFalse(c.shouldRun()) }
        assertTrue(c.shouldRun()) // the probe
        c.record(true) // it pruned → the relaxation is useful again
        assertEquals(LpEffort.BOUND, c.rung, "a pruning re-probe must reactivate the LP")
        assertTrue(c.shouldRun(), "a reactivated LP runs every eligible node again")
    }

    @Test
    fun `the cut rung is shed before the bound`() {
        // A never-pruning CUTS ladder descends one rung per cold window: CUTS → BOUND (cuts off, bound
        // still runs) → OFF. The cut tier, the most expensive, is shed first.
        val c = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            assertTrue(c.shouldRun())
            assertTrue(c.cutsEnabled, "cuts run while at the top rung")
            c.record(false)
        }
        assertEquals(LpEffort.BOUND, c.rung, "a cold window sheds cuts first, keeping the bound")

        repeat(4) {
            assertTrue(c.shouldRun())
            assertFalse(c.cutsEnabled, "cuts no longer run at the BOUND rung")
            c.record(false)
        }
        assertEquals(LpEffort.OFF, c.rung, "a second cold window sheds the bound too")
    }

    @Test
    fun `the cut rung is demoted when its prunes do not justify its cost`() {
        // Reward-driven (#33): one prune per window keeps the bare BOUND rung (cost 1) but not the cut
        // rung (cost 3) — the cuts prune, yet not enough to earn their extra re-solves.
        val cuts = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4, cutCostWeight = 3)
        repeat(4) {
            cuts.shouldRun()
            cuts.record(it == 0) // exactly one prune in the window
        }
        assertEquals(LpEffort.BOUND, cuts.rung, "one prune does not clear the cut rung's 1 × 3 floor")

        val bound = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, cutCostWeight = 3)
        repeat(4) {
            bound.shouldRun()
            bound.record(it == 0) // the same single prune
        }
        assertEquals(LpEffort.BOUND, bound.rung, "the cheaper bound holds on one prune")
    }
}
