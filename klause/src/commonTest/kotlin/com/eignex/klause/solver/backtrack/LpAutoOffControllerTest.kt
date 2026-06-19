package com.eignex.klause.solver.backtrack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #614: the adaptive per-node LP auto-off controller. Verified in isolation (no solver), since the
 * controller is deliberately count-based and therefore deterministic. Covers the two failure modes
 * of the static #562 one-shot it replaces — irreversibility and a single early prune pinning the LP
 * on forever — plus the boundedness that keeps it from regressing the bool-heavy class.
 */
class LpAutoOffControllerTest {

    /** Drive `n` LP-eligible nodes, recording [pruned] for each that actually ran; returns run count. */
    private fun LpAutoOff.drive(n: Int, pruned: (Int) -> Boolean): Int {
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
    fun `a never-pruning LP is disabled after the warmup window`() {
        val c = LpAutoOff(warmup = 4, window = 4)
        repeat(4) {
            assertTrue(c.shouldRun())
            c.record(false)
        }
        assertTrue(c.disabled, "four non-pruning passes over the window must disable the LP")
    }

    @Test
    fun `a disabled LP is re-probed on exponential backoff`() {
        val c = LpAutoOff(warmup = 4, window = 4, reprobeBase = 4, reprobeMax = 16)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        assertTrue(c.disabled)

        // First backoff interval is reprobeBase = 4: three skips, then a probe.
        assertFalse(c.shouldRun())
        assertFalse(c.shouldRun())
        assertFalse(c.shouldRun())
        assertTrue(c.shouldRun(), "a probe is due after reprobeBase eligible nodes")
        c.record(false) // probe failed → back off (×2) and stay disabled
        assertTrue(c.disabled)

        // The next interval doubled to 8: seven skips, then a probe.
        repeat(7) { assertFalse(c.shouldRun()) }
        assertTrue(c.shouldRun(), "the backoff interval doubles after a failed probe")
    }

    @Test
    fun `a re-probe that prunes reactivates the LP`() {
        val c = LpAutoOff(warmup = 4, window = 4, reprobeBase = 4)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        repeat(3) { assertFalse(c.shouldRun()) }
        assertTrue(c.shouldRun()) // the probe
        c.record(true) // it pruned → the relaxation is useful again
        assertFalse(c.disabled, "a pruning re-probe must reactivate the LP")
        assertTrue(c.shouldRun(), "a reactivated LP runs every eligible node again")
    }

    @Test
    fun `an early prune does not pin the LP on once it goes cold`() {
        // The #562 limitation: one lucky early prune kept the LP on forever. Here a relaxation that
        // pruned in its first window but then went cold is shed at the next all-cold window.
        val c = LpAutoOff(warmup = 4, window = 4)
        c.shouldRun()
        c.record(true) // one early prune
        repeat(3) {
            c.shouldRun()
            c.record(false)
        }
        assertFalse(c.disabled, "a window with a prune stays enabled")
        repeat(4) {
            c.shouldRun()
            c.record(false)
        } // a fully cold window
        assertTrue(c.disabled, "a relaxation that stops pruning is disabled at a later window")
    }

    @Test
    fun `a consistently pruning LP stays enabled`() {
        val c = LpAutoOff(warmup = 4, window = 4)
        val runs = c.drive(40) { it % 4 == 0 } // exactly one prune per window
        assertFalse(c.disabled, "a relaxation that clears the per-window prune floor stays on")
        assertEquals(40, runs, "an active LP runs every eligible node")
    }

    @Test
    fun `an Int-MAX reprobe base makes a disable irreversible like the static one-shot`() {
        // LpPlan.autoOffReprobe=false wires reprobeBase=Int.MAX_VALUE — the #562 behaviour.
        val c = LpAutoOff(warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            c.shouldRun()
            c.record(false)
        }
        assertTrue(c.disabled)
        repeat(100_000) { assertFalse(c.shouldRun()) } // never re-probed
    }

    @Test
    fun `a never-pruning LP runs only a bounded number of times over many nodes`() {
        val c = LpAutoOff() // defaults: warmup 64, window 64, reprobe 64..8192
        val runs = c.drive(100_000) { false }
        assertTrue(c.disabled)
        // Warmup (≤64) plus a logarithmic tail of backoff re-probes — far below the node count, so
        // the bool-heavy class sees no throughput regression vs the static one-shot.
        assertTrue(runs < 200, "expected a bounded run count over 100k nodes, got $runs")
    }
}
