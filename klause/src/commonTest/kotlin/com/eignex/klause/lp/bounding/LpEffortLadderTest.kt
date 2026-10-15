package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpEffortLadderTest {
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
    fun `a never-pruning bound should be disabled after the warmup window`() {
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4)
        repeat(4) {
            assertTrue(c.shouldRun())
            c.record(false)
        }
        assertEquals(LpEffort.OFF, c.rung)
    }

    @Test
    fun `unsupported cuts should follow the bound-only schedule`() {
        val bound = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, reprobeBase = 4)
        val cuts = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4, reprobeBase = 4)
        cuts.setCutsSupported(false)

        repeat(40) { node ->
            val expected = bound.shouldRun()
            assertEquals(expected, cuts.shouldRun())
            assertFalse(cuts.cutsEnabled)
            if (expected) {
                bound.record(node % 7 == 0)
                cuts.record(node % 7 == 0)
            }
        }
    }

    @Test
    fun `changing cut support should preserve the active reward window`() {
        val ladder = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4)
        ladder.drive(3) { false }

        ladder.setCutsSupported(false)
        ladder.drive(1) { false }

        assertEquals(LpEffort.OFF, ladder.rung)
    }

    @Test
    fun `a re-probe that prunes should promote the ladder back up`() {
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, reprobeBase = 4)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        repeat(3) { assertFalse(c.shouldRun()) }
        assertTrue(c.shouldRun())
        c.record(true)
        assertEquals(LpEffort.BOUND, c.rung)
        assertTrue(c.shouldRun())
    }

    @Test
    fun `the cut rung should be shed before the bound`() {
        val c = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            assertTrue(c.shouldRun())
            assertTrue(c.cutsEnabled)
            c.record(false)
        }
        assertEquals(LpEffort.BOUND, c.rung)

        repeat(4) {
            assertTrue(c.shouldRun())
            assertFalse(c.cutsEnabled)
            c.record(false)
        }
        assertEquals(LpEffort.OFF, c.rung)
    }

    @Test
    fun `the cut rung should be demoted when its prunes do not justify its cost`() {
        // One prune per window keeps the BOUND rung (cost 1) but not the cut rung (cost 3).
        val cuts = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4, cutCostWeight = 3)
        repeat(4) {
            cuts.shouldRun()
            cuts.record(it == 0) // exactly one prune in the window
        }
        assertEquals(LpEffort.BOUND, cuts.rung)

        val bound = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, cutCostWeight = 3)
        repeat(4) {
            bound.shouldRun()
            bound.record(it == 0) // the same single prune
        }
        assertEquals(LpEffort.BOUND, bound.rung)
    }
}
