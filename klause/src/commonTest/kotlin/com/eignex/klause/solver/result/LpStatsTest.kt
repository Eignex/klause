package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpStatsTest {

    @Test
    fun `a demotion is absent from a run that never throttled`() {
        assertFalse(LpStatsSink().snapshot().demoted)
    }

    @Test
    fun `a demotion reaches the snapshot whichever rule decided it`() {
        val sink = LpStatsSink()

        sink.observeDemoted()

        assertTrue(sink.snapshot().demoted)
    }

    @Test
    fun `a demotion on either side survives a merge`() {
        val demoted = LpStatsSink().apply { observeDemoted() }.snapshot()
        val quiet = LpStatsSink().snapshot()

        assertTrue(quiet.mergedWith(demoted).demoted)
        assertTrue(demoted.mergedWith(quiet).demoted)
    }

    @Test
    fun `a demotion by the work rule is recorded without the clock backstop`() {
        val sink = LpStatsSink()

        sink.observeDemoted()

        val stats = sink.snapshot()
        assertTrue(stats.demoted)
        assertFalse(stats.wallBackstop, "the deterministic rule must be distinguishable from the clock")
    }
}
