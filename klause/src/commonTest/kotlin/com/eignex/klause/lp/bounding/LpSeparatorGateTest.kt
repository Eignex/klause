package com.eignex.klause.lp.bounding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpSeparatorGateTest {
    @Test
    fun `an unproductive family should be disabled after the warmup window`() {
        val gate = LpSeparatorGate(count = 1, warmup = 4, window = 4)
        repeat(4) {
            assertTrue(gate.shouldRun(0))
            gate.record(0, productive = false)
        }
        assertFalse(gate.isEnabled(0))
    }

    @Test
    fun `a disabled family should be re-probed on exponential backoff and re-enabled when productive`() {
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
        assertTrue(gate.shouldRun(0))
        gate.record(0, productive = false) // failed probe → back off (×2), stay disabled
        assertFalse(gate.isEnabled(0))

        // The next interval doubled to 8: seven skips, then a productive probe re-enables.
        repeat(7) { assertFalse(gate.shouldRun(0)) }
        assertTrue(gate.shouldRun(0))
        gate.record(0, productive = true)
        assertTrue(gate.isEnabled(0))
    }

    @Test
    fun `families should be gated independently`() {
        val gate = LpSeparatorGate(count = 2, warmup = 4, window = 4, reprobeBase = Int.MAX_VALUE)
        repeat(4) {
            gate.shouldRun(0)
            gate.record(0, productive = false)
            gate.shouldRun(1)
            gate.record(1, productive = true)
        }
        assertFalse(gate.isEnabled(0))
        assertTrue(gate.isEnabled(1))
    }

    @Test
    fun `reprobe delay should saturate at a non-power-of-two ceiling`() {
        val gate = LpSeparatorGate(count = 1, warmup = 1, window = 1, reprobeBase = 2, reprobeMax = 3)
        gate.shouldRun(0)
        gate.record(0, productive = false)

        assertFalse(gate.shouldRun(0))
        assertTrue(gate.shouldRun(0))
        gate.record(0, productive = false)

        repeat(2) { assertFalse(gate.shouldRun(0)) }
        assertTrue(gate.shouldRun(0))
    }
}
