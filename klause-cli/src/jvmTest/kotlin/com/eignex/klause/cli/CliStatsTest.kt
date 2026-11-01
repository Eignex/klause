package com.eignex.klause.cli

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.ArmFailure
import com.eignex.klause.solver.result.ArmSchedule
import com.eignex.klause.solver.result.BooleanProofStats
import com.eignex.klause.solver.result.CoreGuidedStats
import com.eignex.klause.solver.result.LocalSearchResidual
import com.eignex.klause.solver.result.LocalSearchResidualFactor
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.LpBasisVerificationStats
import com.eignex.klause.solver.result.LpCertifierRouteStats
import com.eignex.klause.solver.result.LpCertifierStats
import com.eignex.klause.solver.result.LpContinuationStats
import com.eignex.klause.solver.result.LpPhaseStats
import com.eignex.klause.solver.result.LpRouteSolveStats
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.OpenHintStats
import com.eignex.klause.solver.result.OpenHintUsageStats
import com.eignex.klause.solver.result.OpenTheoryWorkStats
import com.eignex.klause.solver.result.PortfolioStats
import com.eignex.klause.solver.result.PresolveStats
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SchedulingStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SmtStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SourceLpWorkStats
import com.eignex.klause.solver.result.ValueOrderingStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class CliStatsTest {
    @Test
    fun `Boolean proof output should retain native closures and probe subsets separately`() {
        val proof = BooleanProofStats(2, 2, 1, 5000L, 22000L, retainedProbes = 1)
        val stats = SolveStats(portfolio = PortfolioStats(booleanProof = listOf(proof)))

        val pairs = portfolioStatPairs(stats).toMap().filterKeys { it.startsWith("booleanProof.") }

        assertEquals(mapOf("booleanProof.0.tasks" to "2", "booleanProof.0.nativeRefutations" to "2",
            "booleanProof.0.probeSegments" to "1", "booleanProof.0.probeWork" to "5000",
            "booleanProof.0.workerWork" to "22000", "booleanProof.0.retainedProbes" to "1"), pairs)
    }

    @Test
    fun `geometric counters remain absent until a consumer requests qualification`() {
        for (requested in listOf(false, true)) {
            val stats = SolveStats(run = RunStats(backend = "ls"), ls = if (requested) LocalSearchStats(
                geometricProposalRuns = SumResult(1.0), geometricProposalDeclines = SumResult(2.0),
                geometricProposalCandidates = SumResult(3.0),
            ) else LocalSearchStats())

            val pairs = lsStatPairs(stats, 1L).toMap()

            assertEquals(if (requested) "1" else null, pairs["lsGeometricProposalRuns"])
            assertEquals(if (requested) "2" else null, pairs["lsGeometricProposalDeclines"])
            assertEquals(if (requested) "3" else null, pairs["lsGeometricProposalCandidates"])
        }
    }

    @Test
    fun `value ordering should report support and probe costs separately`() {
        val stats = SolveStats(search = SearchStats(valueOrdering = ValueOrderingStats(1, 2, 3, 4, 5, 6)))

        val pairs = searchStatPairs(stats).toMap()

        assertEquals("1", pairs["valueSupportOrders"])
        assertEquals("2", pairs["valueProbeOrders"])
        assertEquals("3", pairs["valueProbes"])
        assertEquals("4", pairs["valueProbeRefutations"])
        assertEquals("5", pairs["valueProbeWork"])
        assertEquals("6", pairs["valueProbeCappedOrders"])
    }


    @Test
    fun `board restart outcomes should be emitted only after a completed observation`() {
        for (observed in listOf(false, true)) {
            val stats = SolveStats(
                run = RunStats(backend = "ls"),
                ls = if (observed) LocalSearchStats(
                    boardRestartChanges = SumResult(2.0), boardRestartDeclines = SumResult(3.0),
                ) else LocalSearchStats(),
            )

            val pairs = lsStatPairs(stats, 1L).toMap()

            assertEquals(if (observed) "2" else null, pairs["lsBoardRestartChanges"])
            assertEquals(if (observed) "3" else null, pairs["lsBoardRestartDeclines"])
        }
    }

    @Test
    fun `complete traversal Boolean progress should stay absent until observed`() {
        for (observed in listOf(false, true)) {
            val stats = if (observed) {
                SmtStats(
                    completeSearchBooleanVariables = 0,
                    completeSearchMinUnassignedBooleans = 0,
                    completeSearchLinearChecks = 0,
                    completeSearchLinearPoints = 0,
                    completeSearchLinearOutcomes = mapOf("NO_RESULT" to 2L),
                    completeSearchStages = mapOf("RECOVERY_ATTEMPT" to 0L),
                )
            } else {
                SmtStats()
            }

            val pairs = openTheoryStatPairs(SolveStats(smt = stats), 1L).toMap()

            assertEquals(if (observed) "0" else null, pairs["smtCompleteSearchBooleanVariables"])
            assertEquals(if (observed) "0" else null, pairs["smtCompleteSearchMinUnassignedBooleans"])
            assertEquals(if (observed) "0" else null, pairs["smtCompleteSearchLinearChecks"])
            assertEquals(if (observed) "0" else null, pairs["smtCompleteSearchLinearPoints"])
            assertEquals(if (observed) "2" else null, pairs["smtCompleteSearchLinearOutcome_NO_RESULT"])
            assertEquals(if (observed) "0" else null, pairs["smtCompleteSearchStage_RECOVERY_ATTEMPT"])
        }
    }

    @Test
    fun `construction fields should be omitted independently until their stages are observed`() {
        val admitted = SolveStats(openHints = OpenHintStats(usage = OpenHintUsageStats(indexesAdmitted = 1)))
        val rawUsage = OpenHintUsageStats(
            sourceBooleansMax = null,
            sourceClausesMax = null,
            sourcePrimitiveEntriesMax = null,
            rawClausesMax = 0,
            rawLiteralsMax = 0,
            rawIncidencesMax = 0,
            rawVariablesMax = 0,
            rawLedgerDebits = 0,
        )
        val raw = SolveStats(openHints = OpenHintStats(usage = rawUsage))
        val complete = SolveStats(
            openHints = OpenHintStats(
                usage = rawUsage.copy(
                    sourceBooleansMax = 0,
                    sourceClausesMax = 0,
                    sourcePrimitiveEntriesMax = 0,
                ),
            ),
        )

        val legacyPairs = openHintUsageStatPairs(admitted).toMap()
        assertEquals("1", legacyPairs["openHintIndexAdmitted"])
        assertEquals(null, legacyPairs["openHintRawClausesMax"])
        assertEquals(null, legacyPairs["openHintRawLedgerDebits"])
        assertEquals(null, legacyPairs["openHintSourceBooleansMax"])
        val rawPairs = openHintUsageStatPairs(raw).toMap()
        assertEquals("0", rawPairs["openHintRawClausesMax"])
        assertEquals("0", rawPairs["openHintRawLedgerDebits"])
        assertEquals(null, rawPairs["openHintSourceBooleansMax"])
        assertEquals(null, rawPairs["openHintSourcePrimitiveEntriesMax"])
        val observedPairs = openHintUsageStatPairs(complete).toMap()
        assertEquals("0", observedPairs["openHintSourceBooleansMax"])
        assertEquals("0", observedPairs["openHintSourceClausesMax"])
        assertEquals("0", observedPairs["openHintSourcePrimitiveEntriesMax"])
        assertEquals("0", observedPairs["openHintRawClausesMax"])
    }

    @Test
    fun `component hint usage should be emitted when no Boolean proposal was drawn`() {
        val stats = SolveStats(
            openHints = OpenHintStats(
                usage = OpenHintUsageStats(
                    indexesAdmitted = 1, componentConsultations = 3, componentSelections = 2, gateSelections = 2,
                    clauseIndexesAdmitted = 1, clauseConsultations = 3, clauseSelections = 1, clauseDeclines = 2,
                    earlyPointUnavailable = 1, forwardUncovered = 2, primitiveProviderNone = 3,
                    sourceBooleansMax = 9, sourceClausesMax = 7, sourcePrimitiveEntriesMax = 5,
                    rawClausesMax = 3, rawLiteralsMax = 11, rawIncidencesMax = 7, rawVariablesMax = 4,
                    rawLedgerDebits = 19,
                ),
            ),
        )

        val pairs = openTheoryStatPairs(stats, 1L).toMap()

        assertEquals("1", pairs["openHintIndexAdmitted"])
        assertEquals("3", pairs["openHintComponentConsulted"])
        assertEquals("2", pairs["openHintComponentSelected"])
        assertEquals("2", pairs["openHintGateSelected"])
        assertEquals("1", pairs["openHintClauseIndexAdmitted"])
        assertEquals("3", pairs["openHintClauseConsulted"])
        assertEquals("1", pairs["openHintClauseSelected"])
        assertEquals("2", pairs["openHintClauseDeclined"])
        assertEquals("1", pairs["openHintEarlyPointUnavailable"])
        assertEquals("2", pairs["openHintForwardUncovered"])
        assertEquals("3", pairs["openHintPrimitiveProviderNone"])
        assertEquals("9", pairs["openHintSourceBooleansMax"])
        assertEquals("7", pairs["openHintSourceClausesMax"])
        assertEquals("5", pairs["openHintSourcePrimitiveEntriesMax"])
        assertEquals("3", pairs["openHintRawClausesMax"])
        assertEquals("11", pairs["openHintRawLiteralsMax"])
        assertEquals("7", pairs["openHintRawIncidencesMax"])
        assertEquals("4", pairs["openHintRawVariablesMax"])
        assertEquals("19", pairs["openHintRawLedgerDebits"])
        assertEquals(null, pairs["openHintDraws"])
        assertTrue(openHintUsageStatPairs(SolveStats.EMPTY).isEmpty())
    }

    @Test
    fun `core bounds should preserve exact integers when worker snapshots combine`() {
        val first = SolveStats(coreGuided = CoreGuidedStats(1, 2, parseBigInt("9007199254740993")))
        val second = SolveStats(coreGuided = CoreGuidedStats(2, 3, parseBigInt("9007199254740995")))

        val pairs = searchStatPairs(first.mergedWith(second)).toMap()

        assertEquals("9007199254740995", pairs["coreGuidedLowerBound"])
        assertEquals("3", pairs["coreGuidedCores"])
        assertEquals("3", pairs["coreGuidedLargestCore"])
    }

    @Test
    fun `presolve should print native construction separately from converted phase work`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyList()).bake(budget.orSpent(Cancellation.Never))
        val stats = SolveStats(
            presolve = PresolveStats(
                effort = presolveEffortStats(PresolveConfig.NONE, budget, Duration.ZERO),
            ),
        )

        val pairs = presolveStatPairs(stats).toMap()

        assertEquals("1", pairs["presolveRootConstructionEntries"])
        assertEquals("0", pairs["presolveRootPropagationWork"])
        assertEquals("53", pairs["presolveWork"])
        assertEquals("0", pairs["presolveProbeCalls"])
    }

    @Test
    fun `local search phase times should print before the first move`() {
        val stats = SolveStats(
            run = RunStats(backend = "mixed"),
            ls = LocalSearchStats(phaseNanos = mapOf("preparation" to 7L, "seeding" to 0L)),
        )

        val pairs = lsStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("7", pairs["lsPhaseNanos.preparation"])
        assertEquals("0", pairs["lsPhaseNanos.seeding"])
        assertTrue(lsStatPairs(SolveStats.EMPTY, solveTimeMs = 0).isEmpty())
    }

    @Test
    fun `objective scoring observations should print explicit zero indexed use before any move`() {
        val stats = SolveStats(
            run = RunStats(backend = "mixed"),
            ls = LocalSearchStats(objectiveScoring = mapOf("plain" to 1L, "indexed" to 0L)),
        )

        val pairs = lsStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("1", pairs["lsObjectiveScoring.plain"])
        assertEquals("0", pairs["lsObjectiveScoring.indexed"])
    }

    @Test
    fun `a residual observation should print its cost and kind totals before any move`() {
        val stats = SolveStats(
            run = RunStats(backend = "mixed"),
            ls = LocalSearchStats(
                bestResidual = LocalSearchResidual(
                    9,
                    mapOf("Linear" to 7L, "Clause" to 2L),
                    listOf(LocalSearchResidualFactor(3, "Linear", 7, mapOf(2 to false), mapOf(4 to 3L), 1, 5)),
                ),
            ),
        )

        val pairs = lsStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("9", pairs["lsBestResidualViolation"])
        assertEquals("7", pairs["lsBestResidual.Linear"])
        assertEquals("2", pairs["lsBestResidual.Clause"])
        assertEquals(
            "kind=Linear degree=7 bools=b2:false ints=i4:3 omittedBools=1 omittedInts=5",
            pairs["lsBestResidualFactor.3"],
        )
        assertTrue("lsIncumbentObjective" !in pairs)
        assertTrue(pairs.keys.none { it.startsWith("lsBestResidualFactorDetails.") })
    }

    @Test
    fun `residual detail pairs should preserve signed values without changing the coordinate pair`() {
        val stats = SolveStats(
            ls = LocalSearchStats(
                bestResidual = LocalSearchResidual(
                    5,
                    mapOf("Linear" to 5L),
                    listOf(
                        LocalSearchResidualFactor(
                            3,
                            "Linear",
                            5,
                            emptyMap(),
                            mapOf(4 to 2L),
                            details = mapOf(
                                "term.0" to "-3:i4",
                                "activity" to "-6",
                                "op" to "EQ",
                                "bound" to "-1",
                                "i4.definition.kind" to "IntDef.Lin",
                                "i4.definition.inputs" to "i2,b1",
                                "i4.definition.omittedInputs" to "1",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val pairs = lsStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals(
            "kind=Linear degree=5 bools=- ints=i4:2 omittedBools=0 omittedInts=0",
            pairs["lsBestResidualFactor.3"],
        )
        assertEquals("-3:i4", pairs["lsBestResidualFactorDetails.3.term.0"])
        assertEquals("-6", pairs["lsBestResidualFactorDetails.3.activity"])
        assertEquals("-1", pairs["lsBestResidualFactorDetails.3.bound"])
        assertEquals("i2,b1", pairs["lsBestResidualFactorDetails.3.i4.definition.inputs"])
        assertEquals("1", pairs["lsBestResidualFactorDetails.3.i4.definition.omittedInputs"])
        assertTrue(lsStatPairs(SolveStats.EMPTY, solveTimeMs = 0).isEmpty())
    }

    @Test
    fun `refused float phase costs should be emitted without a node solve`() {
        val stats = SolveStats(
            lp = LpStats(
                phases = mapOf(
                    "CLEANUP_STANDALONE" to
                        LpPhaseStats(1L, 13L, 17L, 2L, outcomes = mapOf("PIVOTS" to 1L)),
                ),
            ),
        )

        val pairs = lpStatPairs(stats).toMap()

        assertEquals("1", pairs["lpPhase_CLEANUP_STANDALONE_Calls"])
        assertEquals("13", pairs["lpPhase_CLEANUP_STANDALONE_Nanos"])
        assertEquals("17", pairs["lpPhase_CLEANUP_STANDALONE_Work"])
        assertEquals("2", pairs["lpPhase_CLEANUP_STANDALONE_Pivots"])
        assertEquals("1", pairs["lpPhase_CLEANUP_STANDALONE_PIVOTS"])
    }

    @Test
    fun `continuation costs should remain visible for LP and SMT declines`() {
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
    fun `rational basis resource work should be visible without a float solve`() {
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
    fun `presolve stats should report fired passes and the constraint drop under presolve-prefixed keys`() {
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
    fun `a node pass without a solve should still emit the LP block`() {
        val pairs = lpStatPairs(
            SolveStats(lp = LpStats(nodePasses = SumResult(1.0))),
        ).toMap()

        assertEquals("1", pairs["lpNodePasses"])
        assertEquals("0", pairs["lpSolves"])
    }

    @Test
    fun `root route should emit warm refactor and numerical metrics`() {
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
    fun `cut accounting without an active measurement should omit the maximum`() {
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
    fun `LP stats should derive the bound-pruned split and rates`() {
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
    fun `continuous root statistics should preserve the complete floating bound`() {
        for (bound in listOf(0.123456789, -0.123456789, 9007199254740994.0, Double.MAX_VALUE)) {
            val stats = SolveStats(
                run = RunStats(backend = "backtrack"),
                lp = LpStats(solves = SumResult(1.0), rootBound = bound),
            )

            val published = lpStatPairs(stats).toMap().getValue("lpRootBound")

            assertEquals(bound, published.toDouble())
        }
    }

    @Test
    fun `an uncalled certifier should report no success rate`() {
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
    fun `lagrangian or energetic prunes alone should still emit the block`() {
        val stats = SolveStats(
            run = RunStats(backend = "backtrack"),
            scheduling = SchedulingStats(energeticPruned = SumResult(4.0)),
        )
        val m = lpStatPairs(stats).toMap()
        assertEquals("4", m["lpEnergeticPruned"])
        assertTrue("lpRootBound" !in m)
    }

    @Test
    fun `ls backend should emit the block before any move`() {
        val pairs = lsStatPairs(SolveStats(run = RunStats(backend = "ls")), solveTimeMs = 0)
        assertTrue(pairs.isNotEmpty())
        assertEquals("0", pairs.toMap()["lsMoves"])
    }

    @Test
    fun `ls stats should derive move rates and the incumbent`() {
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
        assertEquals("0.5", m["lsTimeToBest"])
        assertEquals("12", m["lsIncumbentObjective"])
        assertEquals("0", m["lsIncumbentViolation"])
    }

    @Test
    fun `infeasible ls run should report residual violation and no objective`() {
        val stats = SolveStats(
            run = RunStats(backend = "ls"),
            ls = LocalSearchStats(moves = SumResult(800.0), incumbentViolation = 7.0),
        )
        val m = lsStatPairs(stats, solveTimeMs = 0).toMap()
        assertEquals("7", m["lsIncumbentViolation"])
        assertTrue("lsIncumbentObjective" !in m)
        assertTrue("lsTimeToBest" !in m)
    }

    @Test
    fun `SMT stats should report source work without zero-denominator rates`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira"),
            openTheory = OpenTheoryWorkStats(openTheoryChecks = 5),
            smt = SmtStats(sourceLp = SourceLpWorkStats(operations = 2, modeledWork = 7, floatWork = 3,
                boundCertificationWork = 11, conflictCertificationWork = 13)),
        )

        val pairs = openTheoryStatPairs(stats, solveTimeMs = 0).toMap()

        assertEquals("2", pairs["smtSourceLpOperations"])
        assertEquals("7", pairs["smtSourceLpModeledWork"])
        assertEquals("3", pairs["smtSourceLpFloatWork"])
        assertEquals("11", pairs["smtSourceLpBoundCertificationWork"])
        assertEquals("13", pairs["smtSourceLpConflictCertificationWork"])
        assertTrue("smtPrivateChecks" !in pairs)
        assertTrue("smtSimplexAttempts" !in pairs)
        assertEquals("5", pairs["smtTheoryChecks"])
        assertTrue("smtTheoryChecksPerSec" !in pairs)
        assertTrue("smtLiteralsPerExplainedConflict" !in pairs)
        assertTrue("smtWitnessAcceptance" !in pairs)
    }

    @Test
    fun `SMT check rate should use the whole solve duration`() {
        val stats = SolveStats(
            run = RunStats(backend = "exact-lira", wallMs = 10),
            openTheory = OpenTheoryWorkStats(openTheoryChecks = 100),
            smt = SmtStats(reductionRequests = 1),
        )

        val pairs = openTheoryStatPairs(stats, solveTimeMs = 1_000).toMap()

        assertEquals("100", pairs["smtTheoryChecksPerSec"])
    }

    @Test
    fun `the root coefficient spread should keep the magnitude of a small coefficient`() {
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
    fun `search pairs should report the mean learned clause size and literal block distance`() {
        val search = SearchStats(
            learnedClauses = SumResult(4.0),
            learnedLiterals = SumResult(10.0),
            learnedLbd = SumResult(6.0),
        )

        val pairs = searchStatPairs(SolveStats(search = search)).toMap()

        assertEquals("2.5" to "1.5", pairs["learnedMeanSize"] to pairs["learnedMeanLbd"])
    }

    @Test
    fun `portfolio pairs should report each arm with its schedule and credit`() {
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
        val stats = SolveStats(
            portfolio = PortfolioStats(
                listOf(arm),
                reseedStaleThreshold = 4,
                baseSliceWork = 1_000L,
                maxSliceWork = 8_000L,
                sliceGrowth = 2.0,
            ),
        )

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
        assertEquals("1000", pairs["portfolioBaseSliceWork"])
        assertEquals("8000", pairs["portfolioMaxSliceWork"])
        assertEquals("2.0", pairs["portfolioSliceGrowth"])
    }

    @Test
    fun `an exhausted zero solve allowance should report its effective remaining work`() {
        val pairs = nodeBudgetStatPairs(NodeBudget(0L)).toMap()

        assertEquals("0", pairs["nodeBudgetLimit"])
        assertEquals("0", pairs["nodeBudgetSpent"])
        assertEquals("0", pairs["nodeBudgetRemaining"])
    }
}
