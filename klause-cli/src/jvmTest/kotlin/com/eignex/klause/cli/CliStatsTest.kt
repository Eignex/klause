package com.eignex.klause.cli

import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Formatting + gating of the `-s` LP-success block ([lpStatPairs]). */
class CliStatsTest {

    @Test
    fun `presolve stats report which passes fired and the constraint drop, all presolve-prefixed`() {
        val stats = SolveStats(
            backend = "backtrack",
            presolve = PresolveStats(passes = listOf("strengthen", "affine"), constraintsRemoved = 3),
        )
        val pairs = presolveStatPairs(stats)
        val m = pairs.toMap()
        assertEquals("strengthen,affine", m["presolvePasses"])
        assertEquals("3", m["presolveConstraintsRemoved"])
        for ((k, _) in pairs) assertTrue(k.startsWith("presolve"), "key not presolve-prefixed: $k")
    }

    @Test
    fun `no presolve activity emits nothing`() {
        assertTrue(presolveStatPairs(SolveStats.EMPTY).isEmpty(), "no presolve summary")
        assertTrue(
            presolveStatPairs(SolveStats(presolve = PresolveStats())).isEmpty(),
            "a no-op presolve emits nothing",
        )
    }

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
            lp = LpStats(
                solves = SumResult(8.0),
                pruned = SumResult(5.0),
                infeasible = SumResult(2.0),
                pivots = SumResult(20.0),
                seeded = SumResult(4.0),
                rootBound = 12.5,
                ms = 7L,
            ),
            lagrangianPruned = SumResult(1.0),
            energeticPruned = SumResult(3.0),
        )
        val pairs = lpStatPairs(stats)
        assertTrue(pairs.isNotEmpty())
        for ((k, _) in pairs) assertTrue(k.startsWith("lp"), "key not lp-prefixed: $k")
    }

    @Test
    fun `derived split and rates are correct`() {
        val stats = SolveStats(
            backend = "backtrack",
            lp = LpStats(
                solves = SumResult(8.0),
                pruned = SumResult(5.0),
                infeasible = SumResult(2.0),
                pivots = SumResult(20.0),
                rootBound = 12.5,
            ),
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

    @Test
    fun `no ls activity emits nothing`() {
        assertTrue(lsStatPairs(SolveStats.EMPTY).isEmpty())
        // A complete backend that never ran LS: nothing in the LS block.
        assertTrue(lsStatPairs(SolveStats(backend = "backtrack", nodes = SumResult(10.0))).isEmpty())
    }

    @Test
    fun `ls backend emits the block even before any move`() {
        // An LS solve that found feasibility immediately still identifies as the LS engine.
        val pairs = lsStatPairs(SolveStats(backend = "ls"))
        assertTrue(pairs.isNotEmpty())
        assertEquals("0", pairs.toMap()["lsMoves"])
    }

    @Test
    fun `every emitted key is ls-prefixed`() {
        val stats = SolveStats(
            backend = "ls",
            moves = SumResult(1000.0),
            restarts = SumResult(5.0),
            stalls = SumResult(3.0),
            timeToBestMs = 500L,
            incumbentObjective = 12.0,
            incumbentViolation = 0.0,
            wallMs = 2000L,
        )
        val pairs = lsStatPairs(stats)
        assertTrue(pairs.isNotEmpty())
        for ((k, _) in pairs) assertTrue(k.startsWith("ls"), "key not ls-prefixed: $k")
    }

    @Test
    fun `derived ls rates and incumbent are correct`() {
        val stats = SolveStats(
            backend = "ls",
            moves = SumResult(1000.0),
            restarts = SumResult(5.0),
            stalls = SumResult(3.0),
            timeToBestMs = 500L,
            incumbentObjective = 12.0,
            incumbentViolation = 0.0,
            wallMs = 2000L,
        )
        val m = lsStatPairs(stats).toMap()
        assertEquals("1000", m["lsMoves"])
        assertEquals("5", m["lsRestarts"])
        assertEquals("3", m["lsStalls"])
        assertEquals("500", m["lsMovesPerSec"]) // 1000 / (2000ms = 2s)
        assertEquals("0.5", m["lsTimeToBest"]) // 500ms
        assertEquals("12", m["lsIncumbentObjective"])
        assertEquals("0", m["lsIncumbentViolation"])
    }

    @Test
    fun `infeasible ls run reports residual violation and no objective`() {
        val stats = SolveStats(backend = "ls", moves = SumResult(800.0), incumbentViolation = 7.0)
        val m = lsStatPairs(stats).toMap()
        assertEquals("7", m["lsIncumbentViolation"])
        assertTrue("lsIncumbentObjective" !in m, "no objective when never feasible")
        assertTrue("lsTimeToBest" !in m, "no time-to-best when no incumbent")
    }

    @Test
    fun `mixed portfolio with ls moves still emits the ls block`() {
        val stats = SolveStats(backend = "mixed", moves = SumResult(42.0))
        assertTrue(lsStatPairs(stats).isNotEmpty())
        assertEquals("42", lsStatPairs(stats).toMap()["lsMoves"])
    }
}
