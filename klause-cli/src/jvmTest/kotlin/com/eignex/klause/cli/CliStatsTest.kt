package com.eignex.klause.cli

import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.LpBasisVerificationStats
import com.eignex.klause.solver.result.LpCertifierRouteStats
import com.eignex.klause.solver.result.LpCertifierStats
import com.eignex.klause.solver.result.LpRouteSolveStats
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.OpenHintStats
import com.eignex.klause.solver.result.OpenTheoryWorkStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SchedulingStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SmtStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliStatsTest {

    @Test
    fun `rational basis resource work is visible without a float solve`() {
        val stats = SolveStats(lp = LpStats(basisVerification = LpBasisVerificationStats(
            calls = 1L, builds = 2L, restarts = 1L, work = mapOf("FACTOR" to 13L),
            allocation = mapOf("FACTOR" to 23L), declines = mapOf("FILL" to 1L),
            terminalDeclines = mapOf("FACTOR_FILL" to 1L),
        )))

        val pairs = lpStatPairs(stats).toMap()

        assertEquals("1", pairs["lpRationalBasisCalls"])
        assertEquals("2", pairs["lpRationalBasisBuilds"])
        assertEquals("13", pairs["lpRationalBasisWork"])
        assertEquals("23", pairs["lpRationalBasisAllocation"])
        assertEquals("1", pairs["lpRationalBasisDecline_FILL"])
        assertEquals("1", pairs["lpRationalBasisTerminal_FACTOR_FILL"])
    }

    @Test
    fun `presolve stats report which passes fired and the constraint drop, all presolve-prefixed`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
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
        assertTrue(
            lpStatPairs(
                SolveStats(run = RunStats(backend = "backtrack"), search = SearchStats(nodes = SumResult(10.0))),
            ).isEmpty(),
        )
    }

    @Test
    fun `a node pass without a solve still emits the LP block`() {
        val pairs = lpStatPairs(
            SolveStats(lp = LpStats(nodePasses = SumResult(1.0))),
        ).toMap()

        assertEquals("1", pairs["lpNodePasses"])
        assertEquals("0", pairs["lpSolves"])
    }

    @Test
    fun `root route emits warm refactor and numerical metrics`() {
        val pairs = lpStatPairs(
            SolveStats(
                lp = LpStats(
                    rootPasses = SumResult(1.0),
                    rootRoute = LpRouteSolveStats(
                        passes = SumResult(1.0),
                        warmStartAttempts = SumResult(2.0),
                        warmStartHits = SumResult(1.0),
                        initialRefactorizations = SumResult(1.0),
                        numericalRecoveryRefactorizations = SumResult(2.0),
                        singularRefactorizations = SumResult(3.0),
                        smallPivotBails = SumResult(4.0),
                    ),
                ),
            ),
        ).toMap()

        assertEquals("0.5", pairs["lpRootWarmStartHitRate"])
        assertEquals("3", pairs["lpRootRefactorizations"])
        assertEquals("1", pairs["lpRootRefactorInitial"])
        assertEquals("2", pairs["lpRootRefactorNumericalRecovery"])
        assertEquals("3", pairs["lpRootSingularRefactorizations"])
        assertEquals("4", pairs["lpRootSmallPivotBails"])
    }

    @Test
    fun `cut accounting without an active measurement omits the maximum`() {
        val pairs = lpStatPairs(
            SolveStats(
                lp = LpStats(
                    standalonePasses = SumResult(1.0),
                    cutCandidates = SumResult(3.0),
                    cutSelected = SumResult(2.0),
                ),
            ),
        ).toMap()

        assertEquals("3", pairs["lpCutCandidates"])
        assertEquals("2", pairs["lpCutSelected"])
        assertTrue("lpCutActive" !in pairs)
    }

    @Test
    fun `every emitted key is lp-prefixed`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(8.0),
                pruned = SumResult(5.0),
                infeasible = SumResult(2.0),
                pivots = SumResult(20.0),
                seeded = SumResult(4.0),
                rootBound = 12.5,
                ms = 7L,
            ),
            scheduling = SchedulingStats(lagrangianPruned = SumResult(1.0), energeticPruned = SumResult(3.0)),
        )
        val pairs = lpStatPairs(stats)
        assertTrue(pairs.isNotEmpty())
        for ((k, _) in pairs) assertTrue(k.startsWith("lp"), "key not lp-prefixed: $k")
    }

    @Test
    fun `derived split and rates are correct`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(8.0),
                pruned = SumResult(5.0),
                infeasible = SumResult(2.0),
                pivots = SumResult(20.0),
                workOps = SumResult(63.0),
                nodePasses = SumResult(3.0),
                rootBound = 12.5,
            ),
        )
        val m = lpStatPairs(stats).toMap()
        assertEquals("8", m["lpSolves"])
        assertEquals("5", m["lpPruned"])
        assertEquals("2", m["lpInfeasible"])
        assertEquals("3", m["lpBoundPruned"]) // 5 - 2
        assertEquals("0.625", m["lpPruneRate"]) // 5 / 8
        assertEquals("2.5", m["lpPivotsPerSolve"]) // 20 / 8
        assertEquals("21", m["lpWorkOpsPerNode"]) // 63 / 3
        assertEquals("12.5", m["lpRootBound"])
    }

    @Test
    fun `uncalled certifier has no fabricated decline rate`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(1.0),
                integerCertify = LpCertifierStats(
                    attempts = SumResult(1.0),
                    declines = SumResult(1.0),
                    node = LpCertifierRouteStats(attempts = SumResult(1.0), declines = SumResult(1.0)),
                ),
            ),
        )

        val pairs = lpStatPairs(stats).toMap()
        assertEquals("1", pairs["lpIntegerCertifyAttempts"])
        assertEquals("1", pairs["lpIntegerCertifyDeclines"])
        assertEquals("1", pairs["lpIntegerCertifyNodeAttempts"])
        assertEquals("0", pairs["lpIntegerCertifyRootAttempts"])
        assertEquals("0", pairs["lpExactBasisFeasibleAttempts"])
        assertEquals("0", pairs["lpExactBasisFeasibleDeclines"])
        assertTrue("lpExactBasisFeasibleSuccessRate" !in pairs)
    }

    @Test
    fun `lagrangian or energetic prunes alone still emit the block`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            scheduling = SchedulingStats(energeticPruned = SumResult(4.0)),
        )
        val m = lpStatPairs(stats).toMap()
        assertEquals("4", m["lpEnergeticPruned"])
        assertTrue("lpRootBound" !in m, "no root bound when the LP never solved")
    }

    @Test
    fun `no ls activity emits nothing`() {
        assertTrue(lsStatPairs(SolveStats.EMPTY, solveTimeMs = 0).isEmpty())
        assertTrue(
            lsStatPairs(
                SolveStats(run = RunStats(backend = "backtrack"), search = SearchStats(nodes = SumResult(10.0))),
                solveTimeMs = 0,
            ).isEmpty(),
        )
    }

    @Test
    fun `ls backend emits the block even before any move`() {
        val pairs = lsStatPairs(SolveStats(run = RunStats(backend = "ls")), solveTimeMs = 0)
        assertTrue(pairs.isNotEmpty())
        assertEquals("0", pairs.toMap()["lsMoves"])
    }

    @Test
    fun `every emitted key is ls-prefixed`() {
        val stats = SolveStats(
            run = RunStats(backend = "ls", wallMs = 2000L),
            search = SearchStats(restarts = SumResult(5.0)),
            ls = LocalSearchStats(
                moves = SumResult(1000.0),
                stalls = SumResult(3.0),
                timeToBestMs = 500L,
                incumbentObjective = 12.0,
                incumbentViolation = 0.0,
            ),
        )
        val pairs = lsStatPairs(stats, solveTimeMs = 2_000)
        assertTrue(pairs.isNotEmpty())
        for ((k, _) in pairs) assertTrue(k.startsWith("ls"), "key not ls-prefixed: $k")
    }

    @Test
    fun `derived ls rates and incumbent are correct`() {
        val stats = SolveStats(
            run = RunStats(backend = "ls", wallMs = 2000L),
            search = SearchStats(restarts = SumResult(5.0)),
            ls = LocalSearchStats(
                moves = SumResult(1000.0),
                stalls = SumResult(3.0),
                timeToBestMs = 500L,
                incumbentObjective = 12.0,
                incumbentViolation = 0.0,
            ),
        )
        val m = lsStatPairs(stats, solveTimeMs = 2_000).toMap()
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
        val stats = SolveStats(
            run = RunStats(backend = "ls"),
            ls = LocalSearchStats(moves = SumResult(800.0), incumbentViolation = 7.0),
        )
        val m = lsStatPairs(stats, solveTimeMs = 0).toMap()
        assertEquals("7", m["lsIncumbentViolation"])
        assertTrue("lsIncumbentObjective" !in m, "no objective when never feasible")
        assertTrue("lsTimeToBest" !in m, "no time-to-best when no incumbent")
    }

    @Test
    fun `mixed portfolio with ls moves still emits the ls block`() {
        val stats = SolveStats(run = RunStats(backend = "mixed"), ls = LocalSearchStats(moves = SumResult(42.0)))
        assertTrue(lsStatPairs(stats, solveTimeMs = 0).isNotEmpty())
        assertEquals("42", lsStatPairs(stats, solveTimeMs = 0).toMap()["lsMoves"])
    }

    @Test
    fun `an open solve that drew no hint emits no hint keys`() {
        val keys = openTheoryStatPairs(SolveStats(run = RunStats(backend = "exact-lira")), solveTimeMs = 0).toMap().keys
        assertTrue(keys.none { it.startsWith("openHint") }, "hint keys without a draw: $keys")
    }

    @Test
    fun `a drawn hint reports what it covered and cost`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira"),
            openHints = OpenHintStats(draws = 1, produced = 1, hintedVars = 4, steeredSplits = 9, moves = 37),
        )
        val m = openTheoryStatPairs(stats, solveTimeMs = 0).toMap()
        assertEquals("1", m["openHintDraws"])
        assertEquals("1", m["openHintProduced"])
        assertEquals("4", m["openHintVars"])
        assertEquals("9", m["openHintSteered"])
        assertEquals("37", m["openHintMoves"])
    }

    @Test
    fun `a draw that proposed nothing still reports its cost`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira"),
            openHints = OpenHintStats(draws = 1, produced = 0, hintedVars = 0, moves = 20_000),
        )
        val m = openTheoryStatPairs(stats, solveTimeMs = 0).toMap()
        assertEquals("0", m["openHintProduced"])
        assertEquals("0", m["openHintSteered"])
        assertEquals("20000", m["openHintMoves"])
    }

    @Test
    fun `SMT stats distinguish private and shared checks without zero-denominator rates`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira"),
            openTheory = OpenTheoryWorkStats(openTheoryChecks = 5),
            smt = SmtStats(privateChecks = 2, explainedConflicts = 0, witnessCandidates = 0),
        )

        val pairs = openTheoryStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("2", pairs["smtPrivateChecks"])
        assertEquals("3", pairs["smtSharedChecks"])
        assertEquals("5", pairs["smtTheoryChecks"])
        assertTrue("smtTheoryChecksPerSec" !in pairs)
        assertTrue("smtLiteralsPerExplainedConflict" !in pairs)
        assertTrue("smtWitnessAcceptance" !in pairs)
    }

    @Test
    fun `SMT check rate uses the whole solve duration`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira", wallMs = 10),
            openTheory = OpenTheoryWorkStats(openTheoryChecks = 100),
            smt = SmtStats(privateChecks = 1),
        )

        val pairs = openTheoryStatPairs(stats, solveTimeMs = 1_000).toMap()

        assertEquals("100", pairs["smtTheoryChecksPerSec"])
    }

    @Test
    fun `SMT stats are omitted when no exact lane ran`() {
        val pairs = openTheoryStatPairs(SolveStats(run = RunStats(backend = "backtrack")), solveTimeMs = 0).toMap()

        assertTrue("smtTheoryChecks" !in pairs)
    }

    @Test
    fun `numerical trouble is omitted when a run met none`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(solves = SumResult(8.0), pivots = SumResult(20.0)),
        )

        val m = lpStatPairs(stats).toMap()

        assertTrue("lpSingularRefactorizations" !in m)
        assertTrue("lpSmallPivotBails" !in m)
    }

    @Test
    fun `numerical trouble is reported when a run met it`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(8.0),
                singularRefactorizations = SumResult(3.0),
                smallPivotBails = SumResult(2.0),
            ),
        )

        val m = lpStatPairs(stats).toMap()

        assertEquals("3", m["lpSingularRefactorizations"])
        assertEquals("2", m["lpSmallPivotBails"])
    }

    @Test
    fun `the root coefficient spread keeps the magnitude of a small coefficient`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(1.0),
                rootMatrixMinValue = 1.0e-7,
                rootMatrixMaxValue = 2.5e6,
                rootRowRatio = 1.0e9,
            ),
        )

        val m = lpStatPairs(stats).toMap()

        assertEquals("1.0E-7", m["lpRootMatrixMin"])
        assertEquals("2500000.0", m["lpRootMatrixMax"])
        assertEquals("1.0E9", m["lpRootRowRatio"])
    }

    @Test
    fun `the root coefficient spread is omitted when never measured`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(solves = SumResult(8.0)),
        )

        val m = lpStatPairs(stats).toMap()

        assertTrue("lpRootMatrixMin" !in m)
    }
}
