package com.eignex.klause.cli

import com.eignex.klause.solver.result.SolveStats
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Formatting + gating of the `-s` LP-success block ([lpStatPairs]). */
class CliStatsTest {

    @Test
    fun `no lp activity emits nothing`() {
        assertTrue(lpStatPairs(SolveStats.EMPTY).isEmpty())
        // Backend set but the LP never ran: still nothing.
        assertTrue(lpStatPairs(SolveStats(backend = "backtrack", nodes = SumResult(10.0))).isEmpty())
    }

    @Test
    fun `every emitted key is lp-prefixed`() {
        val stats = SolveStats(
            backend = "backtrack",
            lpSolves = SumResult(8.0),
            lpPruned = SumResult(5.0),
            lpInfeasible = SumResult(2.0),
            lpPivots = SumResult(20.0),
            lpSeeded = SumResult(4.0),
            lagrangianPruned = SumResult(1.0),
            energeticPruned = SumResult(3.0),
            rootLpBound = 12.5,
            lpMs = 7L,
        )
        val pairs = lpStatPairs(stats)
        assertTrue(pairs.isNotEmpty())
        for ((k, _) in pairs) assertTrue(k.startsWith("lp"), "key not lp-prefixed: $k")
    }

    @Test
    fun `derived split and rates are correct`() {
        val stats = SolveStats(
            backend = "backtrack",
            lpSolves = SumResult(8.0),
            lpPruned = SumResult(5.0),
            lpInfeasible = SumResult(2.0),
            lpPivots = SumResult(20.0),
            rootLpBound = 12.5,
        )
        val m = lpStatPairs(stats).toMap()
        assertEquals("8", m["lpSolves"])
        assertEquals("5", m["lpPruned"])
        assertEquals("2", m["lpInfeasible"])
        assertEquals("3", m["lpBoundPruned"]) // 5 − 2
        assertEquals("0.625", m["lpPruneRate"]) // 5 / 8
        assertEquals("2.5", m["lpPivotsPerSolve"]) // 20 / 8
        assertEquals("12.5", m["lpRootBound"])
    }

    @Test
    fun `lagrangian or energetic prunes alone still emit the block`() {
        val stats = SolveStats(backend = "backtrack", energeticPruned = SumResult(4.0))
        val m = lpStatPairs(stats).toMap()
        assertEquals("4", m["lpEnergeticPruned"])
        assertTrue("lpRootBound" !in m, "no root bound when the LP never solved")
    }
}
