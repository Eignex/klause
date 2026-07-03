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
            run = RunStats(backend = backend, wallMs = wallMs),
            search = SearchStats(
                nodes = SumResult(nodes),
                peakDepth = MaxResult(peak),
                depthMean = WeightedMeanResult(totalWeights = weights, mean = mean),
            ),
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
        assertEquals(15.0, m.search.nodes.sum)
        assertEquals(9.0, m.search.peakDepth.max)
        assertEquals(40.0, m.search.depthMean.totalWeights)
        // (2*10 + 6*30) / 40 = 5
        assertEquals(5.0, m.search.depthMean.mean)
        assertEquals(7L, m.run.wallMs)
        assertEquals("mixed", m.run.backend)
        assertEquals("backtrack", a.mergedWith(a).run.backend)
    }

    @Test
    fun `lp counters add and root bound takes the tightest finite`() {
        val a = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(8.0),
                pruned = SumResult(5.0),
                infeasible = SumResult(2.0),
                rootBound = 12.0,
                ms = 4L,
            ),
        )
        val b = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(3.0),
                pruned = SumResult(1.0),
                infeasible = SumResult(1.0),
                rootBound = 15.0,
                ms = 6L,
            ),
        )
        val m = a.mergedWith(b)
        assertEquals(11.0, m.lp.solves.sum)
        assertEquals(6.0, m.lp.pruned.sum)
        assertEquals(3.0, m.lp.infeasible.sum)
        assertEquals(15.0, m.lp.rootBound) // same root across workers ⇒ tightest finite bound
        assertEquals(10L, m.lp.ms)
        // NaN root bound defers to the finite side.
        assertEquals(
            12.0,
            a.mergedWith(
                SolveStats(run = RunStats(backend = "backtrack"), lp = LpStats(solves = SumResult(1.0))),
            ).lp.rootBound,
        )
    }

    @Test
    fun `zero-weight mean defers to the populated side`() {
        val a = stats("ls", nodes = 0.0, peak = Double.NEGATIVE_INFINITY, weights = 0.0, mean = Double.NaN, wallMs = 5L)
        val b = stats("ls", nodes = 2.0, peak = 3.0, weights = 4.0, mean = 1.5, wallMs = 1L)
        val m = a.mergedWith(b)
        assertEquals(1.5, m.search.depthMean.mean)
        assertTrue(m.run.timedOut.not())
    }
}
