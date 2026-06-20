package com.eignex.klause.solver.backtrack.lp

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
    fun `a disabled ladder is re-probed on exponential backoff`() {
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, reprobeBase = 4, reprobeMax = 16)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        assertEquals(LpEffort.OFF, c.rung)

        // First backoff interval is reprobeBase = 4: three skips, then a probe.
        assertFalse(c.shouldRun())
        assertFalse(c.shouldRun())
        assertFalse(c.shouldRun())
        assertTrue(c.shouldRun(), "a probe is due after reprobeBase eligible nodes")
        c.record(false) // probe failed → back off (×2) and stay disabled
        assertEquals(LpEffort.OFF, c.rung)

        // The next interval doubled to 8: seven skips, then a probe.
        repeat(7) { assertFalse(c.shouldRun()) }
        assertTrue(c.shouldRun(), "the backoff interval doubles after a failed probe")
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
    fun `an early prune does not pin the LP on once it goes cold`() {
        // The #562 limitation: one lucky early prune kept the LP on forever. Here a relaxation that
        // pruned in its first window but then went cold is shed at the next all-cold window.
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4)
        c.shouldRun()
        c.record(true) // one early prune
        repeat(3) {
            c.shouldRun()
            c.record(false)
        }
        assertEquals(LpEffort.BOUND, c.rung, "a window with a prune stays enabled")
        repeat(4) {
            c.shouldRun()
            c.record(false)
        } // a fully cold window
        assertEquals(LpEffort.OFF, c.rung, "a relaxation that stops pruning is disabled at a later window")
    }

    @Test
    fun `a consistently pruning bound stays enabled`() {
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4)
        val runs = c.drive(40) { it % 4 == 0 } // exactly one prune per window
        assertEquals(LpEffort.BOUND, c.rung, "a relaxation that clears the per-window prune floor stays on")
        assertEquals(40, runs, "an active LP runs every eligible node")
    }

    @Test
    fun `an Int-MAX reprobe base makes a disable irreversible like the static one-shot`() {
        // LpPlan.autoOffReprobe=false wires reprobeBase=Int.MAX_VALUE — the #562 behaviour.
        val c = LpEffortLadder(top = LpEffort.BOUND, warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        assertEquals(LpEffort.OFF, c.rung)
        repeat(100_000) { assertFalse(c.shouldRun()) } // never re-probed
    }

    @Test
    fun `a never-pruning bound runs only a bounded number of times over many nodes`() {
        val c = LpEffortLadder(top = LpEffort.BOUND) // defaults: warmup 64, window 64, reprobe 64..8192
        val runs = c.drive(100_000) { false }
        assertEquals(LpEffort.OFF, c.rung)
        // Warmup (≤64) plus a logarithmic tail of backoff re-probes — far below the node count, so
        // the bool-heavy class sees no throughput regression vs the static one-shot.
        assertTrue(runs < 200, "expected a bounded run count over 100k nodes, got $runs")
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
    fun `a cut ladder starting at CUTS runs cuts while pruning`() {
        val c = LpEffortLadder(top = LpEffort.CUTS, warmup = 4, window = 4)
        repeat(12) {
            assertTrue(c.shouldRun())
            c.record(it % 4 == 0) // one prune per window keeps the top rung
        }
        assertEquals(LpEffort.CUTS, c.rung, "a pruning ladder holds the cut rung")
        assertTrue(c.cutsEnabled)
    }
}
