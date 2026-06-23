package com.eignex.klause.solver.backtrack.lp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #59: the per-separator activity gate. Verified in isolation (no solver), since the controller is
 * count-based and therefore deterministic. It must disable a family that never produces a cut, keep a
 * productive family running, re-probe a disabled family on backoff and re-enable it when a probe is
 * productive, and gate each family independently so disabling one leaves the others untouched.
 */
class LpSeparatorGateTest {

    @Test
    fun `an unproductive family is disabled after the warmup window`() {
        val gate = LpSeparatorGate(count = 1, warmup = 4, window = 4)
        repeat(4) {
            assertTrue(gate.shouldRun(0))
            gate.record(0, productive = false)
        }
        assertFalse(gate.isEnabled(0), "four unproductive rounds over the window must disable the family")
    }

    @Test
    fun `a productive family stays enabled`() {
        val gate = LpSeparatorGate(count = 1, warmup = 4, window = 4)
        repeat(40) {
            assertTrue(gate.shouldRun(0))
            gate.record(0, productive = it % 4 == 0) // one productive round per window suffices
        }
        assertTrue(gate.isEnabled(0), "a family productive at least once per window must hold")
    }

    @Test
    fun `a disabled family is re-probed on exponential backoff and re-enabled when productive`() {
        val gate = LpSeparatorGate(count = 1, warmup = 4, window = 4, reprobeBase = 4, reprobeMax = 16)
        repeat(4) {
            gate.shouldRun(0)
            gate.record(0, productive = false)
        }
        assertFalse(gate.isEnabled(0))

        // First backoff interval is reprobeBase = 4: three skips, then a probe.
        assertFalse(gate.shouldRun(0))
        assertFalse(gate.shouldRun(0))
        assertFalse(gate.shouldRun(0))
        assertTrue(gate.shouldRun(0), "a probe is due after reprobeBase rounds")
        gate.record(0, productive = false) // failed probe → back off (×2), stay disabled
        assertFalse(gate.isEnabled(0))

        // The next interval doubled to 8: seven skips, then a productive probe re-enables.
        repeat(7) { assertFalse(gate.shouldRun(0)) }
        assertTrue(gate.shouldRun(0), "the backoff interval doubles after a failed probe")
        gate.record(0, productive = true)
        assertTrue(gate.isEnabled(0), "a productive probe re-enables the family")
    }

    @Test
    fun `families are gated independently`() {
        val gate = LpSeparatorGate(count = 2, warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            gate.shouldRun(0)
            gate.record(0, productive = false) // family 0 never produces
            gate.shouldRun(1)
            gate.record(1, productive = true) // family 1 always produces
        }
        assertFalse(gate.isEnabled(0), "the unproductive family is disabled")
        assertTrue(gate.isEnabled(1), "the productive family keeps running")
    }

    @Test
    fun `reprobeBase of Int MAX makes a disable irreversible`() {
        val gate = LpSeparatorGate(count = 1, warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            gate.shouldRun(0)
            gate.record(0, productive = false)
        }
        assertFalse(gate.isEnabled(0))
        repeat(10_000) { assertFalse(gate.shouldRun(0), "an irreversible disable never re-probes") }
    }
}
