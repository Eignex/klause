package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.ExactBasisDecline
import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactBasisPhase
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpPhaseMetrics
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.LpSolvePhase
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpStatsTest {
    @Test
    fun `directional branch snapshots retain counts after later observations`() {
        val sink = LpStatsSink()
        sink.observeDirectionalBranch("UP")
        sink.observeDirectionalBranch("UP")
        val before = sink.snapshot()

        sink.observeDirectionalBranch("DOWN")
        sink.observeDirectionalBranch("UP")
        val after = sink.snapshot()

        assertEquals(LpPhaseStats(calls = 2L, outcomes = mapOf("UP" to 2L)),
            before.phases.getValue("DIRECTIONAL_BRANCH_NODE"))
        assertEquals(LpPhaseStats(calls = 4L, outcomes = mapOf("UP" to 3L, "DOWN" to 1L)),
            after.phases.getValue("DIRECTIONAL_BRANCH_NODE"))
    }

    @Test
    fun `template preparation should retain declined work on its owning route`() {
        val sink = LpStatsSink(LpRoute.ROOT)
        sink.observeTemplatePreparation(7L, 11L, prepared = true, factorsReused = 2,
            sourceRebound = true, indexesReused = true, boundsReused = 3,
            componentsReused = 1, componentsDirty = 1, retainedUnits = 17L)
        sink.observeTemplatePreparation(13L, 19L, prepared = false)

        val phase = sink.snapshot().phases.getValue("TEMPLATE_PREPARATION_ROOT")

        assertEquals(LpPhaseStats(calls = 2L, nanos = 20L, work = 30L, outcomes = mapOf(
            "DECLINED" to 1L, "PREPARED" to 1L, "FACTORS_REUSED" to 2L, "SOURCE_REBOUND" to 1L,
            "INDEXES_REUSED" to 1L, "BOUNDS_REUSED" to 3L, "COMPONENTS_REUSED" to 1L,
            "COMPONENTS_DIRTY" to 1L, "RETAINED_UNITS_SUM" to 17L,
        )), phase)
    }

    @Test
    fun `template emission snapshots should remain immutable after further consumers`() {
        val sink = LpStatsSink()
        sink.observeTemplateUse(rows = 2, bounds = 3, exactBounds = 1)
        val before = sink.snapshot()

        sink.observeTemplateUse(rows = 5, exactBounds = 7)
        val after = sink.snapshot()

        assertEquals(mapOf("ROWS" to 2L, "BOUNDS" to 3L, "EXACT_BOUND_OBJECTS" to 1L),
            before.phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes)
        assertEquals(mapOf("ROWS" to 7L, "BOUNDS" to 3L, "EXACT_BOUND_OBJECTS" to 8L),
            after.phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes)
    }

    @Test
    fun `zero template emissions should remain distinct from an unobserved phase`() {
        val sink = LpStatsSink()
        val before = sink.snapshot()

        sink.observeTemplateUse()
        val after = sink.snapshot()

        assertFalse(before.phases.containsKey("TEMPLATE_ASSEMBLY_NODE"))
        assertEquals(mapOf("ROWS" to 0L, "BOUNDS" to 0L, "EXACT_BOUND_OBJECTS" to 0L),
            after.phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes)
    }

    @Test
    fun `bound row snapshots should retain their observed work after further consumers`() {
        val sink = LpStatsSink()
        sink.observeTemplateBoundStorageWork(8L)
        sink.observeTemplateUse(boundChecks = 2, rowsOmitted = 1)
        val before = sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")

        sink.observeTemplateUse(boundChecks = 1, rowsOmitted = 1)
        val after = sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")

        assertEquals(10L, before.work)
        assertEquals(2L, before.outcomes.getValue("BOUND_ROW_CHECKS"))
        assertEquals(1L, before.outcomes.getValue("SOURCE_ROWS_OMITTED"))
        assertEquals(11L, after.work)
        assertEquals(3L, after.outcomes.getValue("BOUND_ROW_CHECKS"))
        assertEquals(2L, after.outcomes.getValue("SOURCE_ROWS_OMITTED"))
    }

    @Test
    fun `unobserved bound work should remain distinct from an observed zero`() {
        val sink = LpStatsSink()
        sink.observeTemplateUse()
        val before = sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")

        sink.observeTemplateBoundStorageWork(0L)
        val after = sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")

        assertFalse(before.outcomes.containsKey("BOUND_ROW_CHECKS"))
        assertFalse(before.outcomes.containsKey("BOUND_COLUMN_WORK"))
        assertEquals(0L, after.outcomes.getValue("BOUND_ROW_CHECKS"))
        assertEquals(0L, after.outcomes.getValue("BOUND_COLUMN_WORK"))
        assertEquals(0L, after.outcomes.getValue("SOURCE_ROWS_OMITTED"))
    }

    @Test
    fun `source residual timing should include component costs on the component route`() {
        val sink = LpStatsSink()
        val metrics = LpSolveMetrics(
            sourceResidualCalls = 2L,
            sourceResidualNanos = 17L,
            sourceResidualScaledCalls = 1L,
        ) + LpSolveMetrics(sourceResidualCalls = 1L, sourceResidualNanos = 5L)

        sink.certificationObserver().observeSolve(metrics, component = true)

        assertEquals(
            LpPhaseStats(calls = 3L, nanos = 22L, outcomes = mapOf("SCALED" to 1L, "UNSCALED" to 2L)),
            sink.snapshot().phases.getValue("SOURCE_RESIDUALS_COMPONENT"),
        )
    }

    @Test
    fun `phase stats should keep outcomes and routes after merging`() {
        val sink = LpStatsSink()
        sink.certificationObserver(LpRoute.STANDALONE).observePhase(
            LpPhaseMetrics(LpSolvePhase.EXACT_DUALS, "STEPS", 13L, steps = 2),
        )
        val before = sink.snapshot()
        sink.certificationObserver(LpRoute.STANDALONE).observePhase(
            LpPhaseMetrics(LpSolvePhase.EXACT_DUALS, "ACCEPTED", 7L, steps = 1),
        )
        sink.certificationObserver(LpRoute.NODE).observePhase(
            LpPhaseMetrics(LpSolvePhase.CLEANUP, "PIVOTS", 19L, work = 23L, pivots = 3),
        )

        val combined = before.mergedWith(sink.snapshot())

        assertEquals(1L, before.phases.getValue("EXACT_DUALS_STANDALONE").calls)
        assertEquals(
            LpPhaseStats(3L, 33L, steps = 5L, outcomes = mapOf("STEPS" to 2L, "ACCEPTED" to 1L)),
            combined.phases.getValue("EXACT_DUALS_STANDALONE"),
        )
        assertEquals(
            LpPhaseStats(1L, 19L, 23L, 3L, outcomes = mapOf("PIVOTS" to 1L)),
            combined.phases.getValue("CLEANUP_NODE"),
        )
    }

    @Test
    fun `continuation stats should keep abandoned work and terminal phase declines`() {
        val sink = LpStatsSink()
        val observer = sink.certificationObserver()
        observer.observeContinuation(
            ExactContinuationMetrics(
                builds = 2, pivots = 3, restarts = 1, work = 19, allocation = 23,
                phase = ContinuationPhase.IMPORT, decline = ContinuationDecline.WORK,
                workByPhase = mapOf(ContinuationPhase.IMPORT to 19),
                allocationByPhase = mapOf(ContinuationPhase.IMPORT to 23),
            ),
        )
        observer.observeContinuation(
            ExactContinuationMetrics(
                pivots = 2,
                resumed = true,
                work = 7,
                workByPhase = mapOf(ContinuationPhase.FEASIBILITY to 7),
                phase = ContinuationPhase.FEASIBILITY,
                decline = ContinuationDecline.WORK,
            ),
        )

        val stats = sink.snapshot().continuation

        assertEquals(2L, stats.calls)
        assertEquals(5L, stats.pivots)
        assertEquals(1L, stats.restarts)
        assertEquals(26L, stats.work.values.sum())
        assertEquals(mapOf("IMPORT_WORK" to 1L, "FEASIBILITY_WORK" to 1L), stats.declines)
    }

    @Test
    fun `basis verification merge should keep failed work and routes`() {
        val first = LpBasisVerificationStats(
            calls = 1L, factoryCalls = 1L, builds = 2L, restarts = 1L,
            work = mapOf("FACTOR" to 19L), allocation = mapOf("FACTOR" to 29L),
            declines = mapOf("WORK" to 1L), terminalDeclines = mapOf("FACTOR_WORK" to 1L), routes = mapOf("NODE" to 1L),
        )
        val second = LpBasisVerificationStats(
            calls = 1L, eligible = 1L, reuse = 1L, solves = 2L, checks = 3L,
            declines = mapOf("WORK" to 1L), terminalDeclines = mapOf("VERIFICATION_WORK" to 1L),
            work = mapOf("FACTOR" to 0L, "VERIFICATION" to 11L), routes = mapOf("COMPONENT" to 1L),
        )

        val combined = first.mergedWith(second)

        assertEquals(2L, combined.calls)
        assertEquals(2L, combined.builds)
        assertEquals(1L, combined.restarts)
        assertEquals(30L, combined.work.values.sum())
        assertEquals(29L, combined.allocation.values.sum())
        assertEquals(mapOf("WORK" to 2L), combined.declines)
        assertEquals(mapOf("FACTOR_WORK" to 1L, "VERIFICATION_WORK" to 1L), combined.terminalDeclines)
        assertEquals(mapOf("NODE" to 1L, "COMPONENT" to 1L), combined.routes)
    }

    @Test
    fun `basis observer should record the terminal phase with the decline reason`() {
        val sink = LpStatsSink()
        val observer = sink.certificationObserver(LpRoute.NODE)
        val metrics = ExactBasisMetrics(
            eligible = true, factoryCalls = 1, builds = 1, reuse = 0, solves = 0, restarts = 0,
            verificationChecks = 0, maxBits = 1, peakFill = 1, operations = emptyList(),
            decline = ExactBasisDecline.WORK, phase = ExactBasisPhase.FACTOR,
        )

        observer.observeBasisVerification(metrics)
        observer.observeBasisVerification(metrics.copy(phase = ExactBasisPhase.VERIFICATION))

        val stats = sink.snapshot().basisVerification
        assertEquals(mapOf("WORK" to 2L), stats.declines)
        assertEquals(mapOf("FACTOR_WORK" to 1L, "VERIFICATION_WORK" to 1L), stats.terminalDeclines)
    }

    @Test
    fun `route and certifier stats should keep zero attempt routes`() {
        val sink = LpStatsSink()
        val observer = sink.certificationObserver(LpRoute.NODE)

        sink.observeNodePass()
        sink.observeEngineCost(LpRoute.STANDALONE, LpSolveMetrics(pivots = 3, workOps = 11, warmAttempts = 1))
        observer.observe(LpCertifier.INTEGER, success = false, LpCertifierCost.Metered(0L))
        observer.observe(LpCertifier.RATIONAL, success = true, LpCertifierCost.Metered(0L))
        observer.observeExactInput(accepted = false)

        val stats = sink.snapshot()
        assertEquals(1.0, stats.standalonePasses.sum)
        assertEquals(1.0, stats.nodePasses.sum)
        assertEquals(0.0, stats.componentPasses.sum)
        assertEquals(1.0, stats.integerCertify.attempts.sum)
        assertEquals(1.0, stats.integerCertify.declines.sum)
        assertEquals(1.0, stats.integerCertify.node.attempts.sum)
        assertEquals(0.0, stats.integerCertify.standalone.attempts.sum)
        assertEquals(1.0, stats.rationalOutcome.successes.sum)
        assertEquals(1.0, stats.exactInputRejections.sum)
    }

    @Test
    fun `cut builds should count every selection but activate only successful builds`() {
        val sink = LpStatsSink()

        sink.observeCutBuild(2) { Unit }
        sink.observeCutBuild(5) { Unit }
        assertFailsWith<IllegalStateException> {
            sink.observeCutBuild(7) { error("build failed") }
        }

        val stats = sink.snapshot()
        assertEquals(14.0, stats.cutSelected.sum)
        assertEquals(5.0, stats.cutActive.max)
    }

    @Test
    fun `component staging attempts should accumulate across observations`() {
        val sink = LpStatsSink()
        val first = LpSolveMetrics(componentScalarStages = 2L, componentRowStages = 3L)
        val copied = first + LpSolveMetrics(componentScalarCopies = 1L)
        val total = copied + LpSolveMetrics(componentJoinStages = 1L)

        sink.observeEngineCost(LpRoute.NODE, first, finished = false)
        sink.observeEngineCost(LpRoute.NODE, copied - first, finished = false)
        sink.observeEngineCost(LpRoute.NODE, total - copied, finished = false)

        val phase = assertNotNull(sink.snapshot().phases["COMPONENT_STAGING_NODE"])
        assertEquals(6L, phase.calls)
        assertEquals(mapOf("SCALAR_ATTEMPT" to 2L, "LOCAL_ROWS_ATTEMPT" to 3L, "JOINED_ATTEMPT" to 1L,
            "SCALAR_COPY" to 1L), phase.outcomes)
    }

    @Test
    fun `auxiliary route costs should not change node totals`() {
        val sink = LpStatsSink()

        sink.observeEngineCost(LpRoute.NODE, LpSolveMetrics(pivots = 2, workOps = 7, initialRefactorizations = 1))
        sink.observeEngineCost(LpRoute.STANDALONE, LpSolveMetrics(pivots = 3, workOps = 11))
        sink.observeEngineCost(LpRoute.COMPONENT, LpSolveMetrics(pivots = 5, workOps = 13))
        sink.observeEngineCost(LpRoute.ROOT, LpSolveMetrics(pivots = 7, workOps = 17))

        val stats = sink.snapshot()
        assertEquals(2.0, stats.pivots.sum)
        assertEquals(7.0, stats.workOps.sum)
        assertEquals(1.0, stats.refactorizations.sum)
        assertEquals(3.0, stats.standalonePivots.sum)
        assertEquals(5.0, stats.componentPivots.sum)
        assertEquals(7.0, stats.rootPivots.sum)
    }

    @Test
    fun `root sink should count root work without node outcomes`() {
        val sink = LpStatsSink(LpRoute.ROOT)

        sink.observeNodePass()
        sink.observeSolve()
        sink.observeEngineCost(LpRoute.NODE, LpSolveMetrics(pivots = 2, workOps = 7))
        sink.observeInfeasiblePrune()
        sink.observeFix()
        sink.observeRootReducedCostFixes(1)

        val stats = sink.snapshot()
        assertEquals(1.0, stats.rootPasses.sum)
        assertEquals(2.0, stats.rootPivots.sum)
        assertEquals(0.0, stats.nodePasses.sum)
        assertEquals(0.0, stats.solves.sum)
        assertEquals(0.0, stats.pruned.sum)
        assertEquals(0.0, stats.fixed.sum)
        assertEquals(0.0, stats.rootReducedCostFixes.sum)
    }

    @Test
    fun `component solve cost should override the sink route`() {
        val sink = LpStatsSink(LpRoute.ROOT)

        sink.certificationObserver().observeSolve(LpSolveMetrics(pivots = 3), component = true)

        val stats = sink.snapshot()
        assertEquals(1.0, stats.componentPasses.sum)
        assertEquals(0.0, stats.rootPasses.sum)
    }

    @Test
    fun `merge should keep a demotion from either side`() {
        val demoted = LpStatsSink().apply { observeDemoted() }.snapshot()
        val quiet = LpStatsSink().snapshot()

        assertTrue(quiet.mergedWith(demoted).demoted)
        assertTrue(demoted.mergedWith(quiet).demoted)
    }

    @Test
    fun `observeDemoted should not mark the work allowance spent`() {
        val sink = LpStatsSink()

        sink.observeDemoted()

        val stats = sink.snapshot()
        assertTrue(stats.demoted)
        assertFalse(stats.workAllowanceSpent)
    }

    @Test
    fun `auxiliary routes should accumulate paused deltas without counting extra passes`() {
        val partial = LpSolveMetrics(pivots = 2, workOps = 7, warmAttempts = 1, warmHits = 1,
            initialRefactorizations = 1, warmStartRefactorizations = 2, singularRecoveryRefactorizations = 3,
            updateLimitRefactorizations = 4, backendRequestedRefactorizations = 5,
            reconcileRecoveryRefactorizations = 6, numericalRecoveryRefactorizations = 7,
            primalRefactorizations = 8, singularRefactorizations = 9, smallPivotBails = 10)
        val final = partial + LpSolveMetrics(pivots = 3, workOps = 11, smallPivotBails = 1)
        val expected = LpRouteSolveStats(
            passes = SumResult(1.0), pivots = SumResult(5.0), workOps = SumResult(18.0),
            warmStartAttempts = SumResult(1.0), warmStartHits = SumResult(1.0),
            initialRefactorizations = SumResult(1.0), warmStartRefactorizations = SumResult(2.0),
            singularRecoveryRefactorizations = SumResult(3.0), updateLimitRefactorizations = SumResult(4.0),
            backendRequestedRefactorizations = SumResult(5.0), reconcileRecoveryRefactorizations = SumResult(6.0),
            numericalRecoveryRefactorizations = SumResult(7.0), primalRefactorizations = SumResult(8.0),
            singularRefactorizations = SumResult(9.0), smallPivotBails = SumResult(11.0),
        )
        for (route in listOf(LpRoute.STANDALONE, LpRoute.COMPONENT, LpRoute.ROOT)) {
            val sink = LpStatsSink(route)
            val observer = sink.certificationObserver()
            observer.observeSolveProgress(partial, component = false, finished = false)
            val paused = sink.snapshot()

            observer.observeSolveProgress(final - partial, component = false, finished = true)
            val stats = sink.snapshot()
            val actual = when (route) {
                LpRoute.STANDALONE -> stats.standaloneRoute
                LpRoute.COMPONENT -> stats.componentRoute
                LpRoute.ROOT -> stats.rootRoute
                LpRoute.NODE -> error("node route")
            }

            assertEquals(expected, actual)
            assertEquals(0.0, paused.standalonePasses.sum + paused.componentPasses.sum + paused.rootPasses.sum)
            assertEquals(1.0, stats.standalonePasses.sum + stats.componentPasses.sum + stats.rootPasses.sum)
            assertEquals(5.0, stats.standalonePivots.sum + stats.componentPivots.sum + stats.rootPivots.sum)
            assertEquals(18.0, stats.standaloneWorkOps.sum + stats.componentWorkOps.sum + stats.rootWorkOps.sum)
            assertEquals(0.0, stats.pivots.sum)
            assertEquals(0.0, stats.solves.sum)
        }
    }

    @Test
    fun `node deltas should retain costs before a solve finishes`() {
        val sink = LpStatsSink()
        sink.observeNodePass()
        val observer = sink.certificationObserver()
        observer.observeSolveProgress(LpSolveMetrics(pivots = 2, workOps = 7, warmAttempts = 1, warmHits = 1,
            warmStartRefactorizations = 1, singularRefactorizations = 2), component = false, finished = false)
        val paused = sink.snapshot()

        observer.observeSolveProgress(LpSolveMetrics(pivots = 3, workOps = 11, primalRefactorizations = 2,
            smallPivotBails = 1), component = false, finished = true)
        sink.observeSolve()
        val stats = sink.snapshot()

        assertEquals(0.0, paused.solves.sum)
        assertEquals(7.0, paused.workOps.sum)
        assertEquals(1.0, stats.nodePasses.sum)
        assertEquals(1.0, stats.solves.sum)
        assertEquals(5.0, stats.pivots.sum)
        assertEquals(18.0, stats.workOps.sum)
        assertEquals(1.0, stats.seeded.sum)
        assertEquals(stats.seeded, stats.warmStartHits)
        assertEquals(1.0, stats.warmStartAttempts.sum)
        assertEquals(3.0, stats.refactorizations.sum)
        assertEquals(2.0, stats.singularRefactorizations.sum)
        assertEquals(1.0, stats.smallPivotBails.sum)
    }

    @Test
    fun `standalone allowance should include only components invoked by standalone consumers`() {
        val sink = LpStatsSink()
        val standalone = sink.certificationObserver(LpRoute.STANDALONE)
        standalone.observeSolveProgress(LpSolveMetrics(workOps = 7), component = true, finished = false)
        val paused = sink.snapshot()

        standalone.observeSolveProgress(LpSolveMetrics(workOps = 11), component = true, finished = true)
        standalone.observeSolve(LpSolveMetrics(workOps = 13), component = false)
        sink.certificationObserver(LpRoute.ROOT).observeSolve(LpSolveMetrics(workOps = 17), component = true)
        val stats = sink.snapshot()

        assertEquals(7.0, paused.componentWorkOps.sum)
        assertEquals(0.0, paused.componentPasses.sum)
        assertEquals(31L, sink.standaloneWork)
        assertEquals(13.0, stats.standaloneWorkOps.sum)
        assertEquals(35.0, stats.componentWorkOps.sum)
        assertEquals(stats.componentWorkOps, stats.componentRoute.workOps)
        assertEquals(2.0, stats.componentPasses.sum)
        assertEquals(stats.componentPasses, stats.componentRoute.passes)
    }

    @Test
    fun `component costs should sum in integer units before snapshot conversion`() {
        val sink = LpStatsSink()
        val largeWork = 9_007_199_254_740_993L
        sink.certificationObserver(LpRoute.STANDALONE).observeSolve(
            LpSolveMetrics(workOps = largeWork), component = true,
        )
        sink.certificationObserver(LpRoute.ROOT).observeSolve(LpSolveMetrics(workOps = 1), component = true)

        val stats = sink.snapshot()

        assertEquals((largeWork + 1L).toDouble(), stats.componentWorkOps.sum)
        assertEquals(stats.componentWorkOps, stats.componentRoute.workOps)
        assertEquals(largeWork, sink.standaloneWork)
    }

    @Test
    fun `worker merge should retain paused work and terminal failed attempt costs`() {
        val paused = LpStatsSink()
        paused.observeEngineCost(LpRoute.ROOT, LpSolveMetrics(pivots = 2, workOps = 7), finished = false)
        val failed = LpStatsSink()
        failed.observeEngineCost(LpRoute.ROOT, LpSolveMetrics(pivots = 3, workOps = 11,
            initialRefactorizations = 1, singularRefactorizations = 1, smallPivotBails = 1))

        val stats = paused.snapshot().mergedWith(failed.snapshot())

        assertEquals(1.0, stats.rootPasses.sum)
        assertEquals(5.0, stats.rootPivots.sum)
        assertEquals(18.0, stats.rootWorkOps.sum)
        assertEquals(stats.rootPasses, stats.rootRoute.passes)
        assertEquals(stats.rootPivots, stats.rootRoute.pivots)
        assertEquals(stats.rootWorkOps, stats.rootRoute.workOps)
        assertEquals(1.0, stats.rootRoute.singularRefactorizations.sum)
        assertEquals(1.0, stats.rootRoute.smallPivotBails.sum)
    }

    @Test
    fun `certifier snapshots and worker merges should preserve route outcomes`() {
        val first = LpStatsSink(LpRoute.ROOT)
        first.certificationObserver().observe(LpCertifier.INTEGER, false, LpCertifierCost.Metered(7))
        val before = first.snapshot()
        first.certificationObserver().observe(LpCertifier.INTEGER, true, LpCertifierCost.Metered(11))
        val second = LpStatsSink()
        second.certificationObserver(LpRoute.COMPONENT).observe(LpCertifier.INTEGER, false, LpCertifierCost.Metered(13))
        second.certificationObserver(LpRoute.STANDALONE).observe(LpCertifier.INTEGER, true, LpCertifierCost.Metered(17))

        val stats = first.snapshot().mergedWith(second.snapshot()).integerCertify

        assertEquals(1.0, before.integerCertify.attempts.sum)
        assertEquals(0.0, before.integerCertify.successes.sum)
        assertEquals(4.0, stats.attempts.sum)
        assertEquals(2.0, stats.successes.sum)
        assertEquals(2.0, stats.declines.sum)
        assertEquals(0.0, stats.node.attempts.sum)
        assertEquals(2.0, stats.root.attempts.sum)
        assertEquals(1.0, stats.root.successes.sum)
        assertEquals(1.0, stats.root.declines.sum)
        assertEquals(1.0, stats.component.declines.sum)
        assertEquals(1.0, stats.standalone.successes.sum)
    }
}
