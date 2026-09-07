package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpStatsTest {

    @Test
    fun `route and certifier observations preserve zero attempt routes`() {
        val sink = LpStatsSink()
        val observer = sink.certificationObserver()

        sink.observeEngineCost(LpRoute.STANDALONE, LpSolveMetrics(pivots = 3, workOps = 11, warmAttempts = 1))
        observer.observe(LpCertifier.INTEGER, success = false)
        observer.observe(LpCertifier.RATIONAL, success = true)
        observer.observeExactInput(accepted = false)

        val stats = sink.snapshot()
        assertEquals(1.0, stats.standalonePasses.sum)
        assertEquals(0.0, stats.nodePasses.sum)
        assertEquals(0.0, stats.componentPasses.sum)
        assertEquals(1.0, stats.integerCertify.attempts.sum)
        assertEquals(1.0, stats.integerCertify.declines.sum)
        assertEquals(1.0, stats.rationalOutcome.successes.sum)
        assertEquals(1.0, stats.exactInputRejections.sum)
    }

    @Test
    fun `engine reasons sum to refactorizations without inference`() {
        val sink = LpStatsSink()

        sink.observeEngineCost(
            LpRoute.NODE,
            LpSolveMetrics(
                initialRefactorizations = 1,
                updateLimitRefactorizations = 2,
                backendRequestedRefactorizations = 1,
            ),
        )

        val stats = sink.snapshot()
        assertEquals(4.0, stats.refactorizations.sum)
        assertEquals(1.0, stats.initialRefactorizations.sum)
        assertEquals(2.0, stats.updateLimitRefactorizations.sum)
        assertEquals(1.0, stats.backendRequestedRefactorizations.sum)
    }

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
