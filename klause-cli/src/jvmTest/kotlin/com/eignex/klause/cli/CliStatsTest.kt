package com.eignex.klause.cli

import com.eignex.klause.solver.result.ArmFailure
import com.eignex.klause.solver.result.ArmSchedule
import com.eignex.klause.solver.result.LocalSearchResidual
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.LpBasisVerificationStats
import com.eignex.klause.solver.result.LpCertifierRouteStats
import com.eignex.klause.solver.result.LpCertifierStats
import com.eignex.klause.solver.result.LpContinuationStats
import com.eignex.klause.solver.result.LpPhaseStats
import com.eignex.klause.solver.result.LpRouteSolveStats
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.OpenHintStats
import com.eignex.klause.solver.result.OpenTheoryClauseStats
import com.eignex.klause.solver.result.OpenTheoryWorkStats
import com.eignex.klause.solver.result.PortfolioStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SchedulingStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SmtStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliStatsTest {

    @Test
    fun `a residual observation prints its cost and kind totals before any move`() {
        val stats = SolveStats(
            run = RunStats(backend = "mixed"),
            ls = LocalSearchStats(bestResidual = LocalSearchResidual(9, mapOf("Linear" to 7L, "Clause" to 2L))),
        )

        val pairs = lsStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("9", pairs["lsBestResidualViolation"])
        assertEquals("7", pairs["lsBestResidual.Linear"])
        assertEquals("2", pairs["lsBestResidual.Clause"])
        assertTrue("lsIncumbentObjective" !in pairs)
    }
    @Test
    fun `refused float phase costs are emitted without a node solve`() {
        val stats = SolveStats(lp = LpStats(phases = mapOf("CLEANUP_STANDALONE" to
            LpPhaseStats(1L, 13L, 17L, 2L, outcomes = mapOf("PIVOTS" to 1L)))))

        val pairs = lpStatPairs(stats).toMap()

        assertEquals("1", pairs["lpPhase_CLEANUP_STANDALONE_Calls"])
        assertEquals("13", pairs["lpPhase_CLEANUP_STANDALONE_Nanos"])
        assertEquals("17", pairs["lpPhase_CLEANUP_STANDALONE_Work"])
        assertEquals("2", pairs["lpPhase_CLEANUP_STANDALONE_Pivots"])
        assertEquals("1", pairs["lpPhase_CLEANUP_STANDALONE_PIVOTS"])
    }

    @Test
    fun `shared conflict coverage is visible in open theory stats`() {
        val stats = SolveStats(
            openTheoryClauses = OpenTheoryClauseStats(assertingConflicts = 7, nonAssertingConflicts = 3),
        )

        val pairs = openTheoryStatPairs(stats, 1L).toMap()

        assertEquals("7", pairs["openAssertingConflicts"])
        assertEquals("3", pairs["openNonAssertingConflicts"])
    }

    @Test
    fun `continuation costs remain visible for LP and SMT declines`() {
        val continuation = LpContinuationStats(
            calls = 1,
            work = mapOf("IMPORT" to 13),
            declines = mapOf("IMPORT_WORK" to 1),
        )
        val stats = SolveStats(lp = LpStats(continuation = continuation), smt = SmtStats(continuation = continuation))

        val lp = lpStatPairs(stats).toMap()
        val smt = openTheoryStatPairs(stats, 1L).toMap()

        assertEquals("13", lp["lpContinuationWork"])
        assertEquals("1", lp["lpContinuationDecline_IMPORT_WORK"])
        assertEquals("13", smt["smtContinuationWork"])
        assertEquals("1", smt["smtContinuationDecline_IMPORT_WORK"])
    }

    @Test
    fun `rational basis resource work is visible without a float solve`() {
        val stats = SolveStats(
            lp = LpStats(
                basisVerification = LpBasisVerificationStats(
                    calls = 1L,
                    builds = 2L,
                    restarts = 1L,
                    work = mapOf("FACTOR" to 13L),
                    allocation = mapOf("FACTOR" to 23L),
                    declines = mapOf("FILL" to 1L),
                    terminalDeclines = mapOf("FACTOR_FILL" to 1L),
                ),
            ),
        )

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
    fun `ls backend emits the block even before any move`() {
        val pairs = lsStatPairs(SolveStats(run = RunStats(backend = "ls")), solveTimeMs = 0)
        assertTrue(pairs.isNotEmpty())
        assertEquals("0", pairs.toMap()["lsMoves"])
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
    fun `SMT stats report source work without zero-denominator rates`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira"),
            openTheory = OpenTheoryWorkStats(openTheoryChecks = 5),
            smt = SmtStats(sourceLp = SourceLpWorkStats(operations = 2, modeledWork = 7, floatWork = 3)),
        )

        val pairs = openTheoryStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("2", pairs["smtSourceLpOperations"])
        assertEquals("7", pairs["smtSourceLpModeledWork"])
        assertEquals("3", pairs["smtSourceLpFloatWork"])
        assertTrue("smtPrivateChecks" !in pairs)
        assertTrue("smtSimplexAttempts" !in pairs)
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
            smt = SmtStats(reductionRequests = 1),
        )

        val pairs = openTheoryStatPairs(stats, solveTimeMs = 1_000).toMap()

        assertEquals("100", pairs["smtTheoryChecksPerSec"])
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
    fun `search pairs report the mean learned clause size and literal block distance`() {
        val search = SearchStats(
            learnedClauses = SumResult(4.0),
            learnedLiterals = SumResult(10.0),
            learnedLbd = SumResult(6.0),
        )

        val pairs = searchStatPairs(SolveStats(search = search)).toMap()

        assertEquals("2.5" to "1.5", pairs["learnedMeanSize"] to pairs["learnedMeanLbd"])
    }

    @Test
    fun `portfolio pairs report each arm with its schedule and credit`() {
        val credit = mapOf("ClauseUses" to 4.0)
        val arm = ArmSchedule(
            "bt-0",
            segments = 3,
            work = 15_000,
            millis = 1_200,
            meanReward = 0.5,
            failures = 0,
            credit = credit,
            maxMillis = 500,
            initializationMillis = 100,
            reseeds = 2,
            failure = ArmFailure(7, "IllegalStateException", "row \"R\"\n\t\\", "slice", 2, 30, "cause\r\nframe"),
        )
        val stats = SolveStats(portfolio = PortfolioStats(listOf(arm), reseedStaleThreshold = 4))

        val pairs = portfolioStatPairs(stats).toMap()

        assertEquals(
            "segments=3 work=15000 ms=1200 reward=0.5 failures=0 faults=0 maxMs=500 initMs=100 reseeds=2 " +
                "initWork=0 initCancelled=0 ClauseUses=4",
            pairs["arm.bt-0"],
        )
        assertEquals(
            "{\"armId\":7,\"phase\":\"slice\",\"segment\":2,\"work\":30,\"type\":\"IllegalStateException\"," +
                "\"message\":\"row \\\"R\\\"\\u000a\\u0009\\\\\",\"trace\":\"cause\\u000d\\u000aframe\"}",
            pairs["armFailure.bt-0"],
        )
        assertEquals("4", pairs["portfolioReseedStaleThreshold"])
    }
}
