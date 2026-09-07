package com.eignex.klause.solver.result

import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpStatsTest {

    @Test
    fun `route and certifier observations preserve zero attempt routes`() {
        val sink = LpStatsSink()
        val observer = sink.certificationObserver(LpRoute.NODE)

        sink.observeNodePass()
        sink.observeEngineCost(LpRoute.STANDALONE, LpSolveMetrics(pivots = 3, workOps = 11, warmAttempts = 1))
        observer.observe(LpCertifier.INTEGER, success = false)
        observer.observe(LpCertifier.RATIONAL, success = true)
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
    fun `node passes count nodes while solves count cut re-solves`() {
        val sink = LpStatsSink()

        sink.observeNodePass()
        sink.observeSolve()
        sink.observeSolve()

        val stats = sink.snapshot()
        assertEquals(1.0, stats.nodePasses.sum)
        assertEquals(2.0, stats.solves.sum)
    }

    @Test
    fun `active cuts preserve the largest simultaneous pool`() {
        val sink = LpStatsSink()

        sink.observeCutAccounting(candidates = 3, selected = 2, active = 2)
        sink.observeCutAccounting(candidates = 4, selected = 1, active = 5)

        assertEquals(5.0, sink.snapshot().cutActive.max)
    }

    @Test
    fun `a cutless separation does not fabricate an active-pool measurement`() {
        val sink = LpStatsSink()

        sink.observeCutAccounting(candidates = 0, selected = 0, active = 0)

        assertFalse(sink.snapshot().cutActive.max.isFinite())
    }

    @Test
    fun `cut builds count every growing selection but activate only successful builds`() {
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
    fun `auxiliary route metrics do not change node cost totals`() {
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
    fun `auxiliary routes preserve warm refactor and numerical metrics`() {
        val sink = LpStatsSink()

        sink.observeEngineCost(
            LpRoute.ROOT,
            LpSolveMetrics(
                warmAttempts = 2,
                warmHits = 1,
                initialRefactorizations = 1,
                primalRefactorizations = 2,
                singularRefactorizations = 3,
                smallPivotBails = 4,
            ),
        )

        val root = sink.snapshot().rootRoute
        assertEquals(1.0, root.passes.sum)
        assertEquals(2.0, root.warmStartAttempts.sum)
        assertEquals(1.0, root.warmStartHits.sum)
        assertEquals(1.0, root.initialRefactorizations.sum)
        assertEquals(2.0, root.primalRefactorizations.sum)
        assertEquals(3.0, root.singularRefactorizations.sum)
        assertEquals(4.0, root.smallPivotBails.sum)
    }

    @Test
    fun `presolve probes count root work without node outcomes`() {
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
    fun `component engines override the caller route for solve cost`() {
        val sink = LpStatsSink(LpRoute.ROOT)

        sink.certificationObserver().observeSolve(LpSolveMetrics(pivots = 3), component = true)

        val stats = sink.snapshot()
        assertEquals(1.0, stats.componentPasses.sum)
        assertEquals(0.0, stats.rootPasses.sum)
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
