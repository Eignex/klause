package com.eignex.klause.portfolio

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PortfolioDiagnosticsTest {
    @Test
    fun `charged coverage should ignore different arm durations`() {
        val traces = listOf(1L, 10_000L).map { duration ->
            val clock = TestTimeSource()
            val trace = mutableListOf<PortfolioEvent>()
            val workers = List(3) { arm ->
                val handle = ScriptedSearch(offers = { arm == 0 }, onSlice = {
                    clock += (if (arm == 0) duration else 1L).milliseconds
                })
                trackingWorker("worker$arm", arm, handle).also {
                    if (arm == 0) it.family = ArmFamily.LocalSearch
                }
            }
            Portfolio.thompson(workers, baseSliceWork = 2, maxSliceWork = 8,
                minShares = doubleArrayOf(0.1, 0.0, 0.0),
                diagnostics = PortfolioDiagnostics(80, 17, trace::add)).use {
                it.runTimeSource = clock
                it.minimize(Cancellation.Never)
            }
            trace
        }

        assertEquals(traces[0], traces[1])
        assertTrue(traces[0].filterIsInstance<PortfolioEvent.Selected>().size > 6)
        assertEquals("work-limit", assertIs<PortfolioEvent.Finished>(traces[0].last()).reason)
    }

    @Test
    fun `emergency cancellation should stop without exposing a scheduling deadline`() {
        val trace = mutableListOf<PortfolioEvent>()
        var stopped = false
        val handle = TrackingResumableSearch(null, onRun = { stopped = true })
        val worker = trackingWorker("worker", 0, handle)
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = stopped
            override fun deadline() = kotlin.time.TimeSource.Monotonic.markNow() + 100.milliseconds
        }

        Portfolio.thompson(listOf(worker), baseSliceWork = 1,
            diagnostics = PortfolioDiagnostics(100, onEvent = trace::add)).use {
            it.minimize(token)
        }

        assertEquals(1, trace.filterIsInstance<PortfolioEvent.Selected>().size)
        assertEquals("external-stop", assertIs<PortfolioEvent.Finished>(trace.last()).reason)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `diagnostics should reject parallel lanes`() {
        val workers = List(2) { trackingWorker("worker$it", it, TrackingResumableSearch(null)) }

        assertFailsWith<IllegalArgumentException> {
            Portfolio.thompson(workers, lanes = 2, diagnostics = PortfolioDiagnostics(100))
        }
        workers.forEach { it.close() }
    }
}
