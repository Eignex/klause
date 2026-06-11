package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolveStatsMergeTest {

    private fun stats(backend: String, nodes: Double, peak: Double, weights: Double, mean: Double, wallMs: Long) =
        SolveStats(
            backend = backend,
            nodes = SumResult(nodes),
            peakDepth = MaxResult(peak),
            depthMean = WeightedMeanResult(totalWeights = weights, mean = mean),
            wallMs = wallMs,
        )

    @Test
    fun `empty is the identity on both sides`() {
        val s = stats("backtrack", nodes = 10.0, peak = 4.0, weights = 10.0, mean = 2.0, wallMs = 7L)
        assertEquals(s, SolveStats.EMPTY.mergedWith(s))
        assertEquals(s, s.mergedWith(SolveStats.EMPTY))
    }

    @Test
    fun `counters add peaks max means weight-combine wall maxes backends mix`() {
        val a = stats("backtrack", nodes = 10.0, peak = 4.0, weights = 10.0, mean = 2.0, wallMs = 7L)
        val b = stats("ls", nodes = 5.0, peak = 9.0, weights = 30.0, mean = 6.0, wallMs = 3L)
        val m = a.mergedWith(b)
        assertEquals(15.0, m.nodes.sum)
        assertEquals(9.0, m.peakDepth.max)
        assertEquals(40.0, m.depthMean.totalWeights)
        // (2*10 + 6*30) / 40 = 5
        assertEquals(5.0, m.depthMean.mean)
        assertEquals(7L, m.wallMs)
        assertEquals("mixed", m.backend)
        assertEquals("backtrack", a.mergedWith(a).backend)
    }

    @Test
    fun `zero-weight mean defers to the populated side`() {
        val a = stats("ls", nodes = 0.0, peak = Double.NEGATIVE_INFINITY, weights = 0.0, mean = Double.NaN, wallMs = 5L)
        val b = stats("ls", nodes = 2.0, peak = 3.0, weights = 4.0, mean = 1.5, wallMs = 1L)
        val m = a.mergedWith(b)
        assertEquals(1.5, m.depthMean.mean)
        assertTrue(m.timedOut.not())
    }
}
