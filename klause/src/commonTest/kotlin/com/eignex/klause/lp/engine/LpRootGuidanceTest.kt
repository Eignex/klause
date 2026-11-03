package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpRootGuidanceTest {
    private val zero = ExactLpNumber.of(0L)
    private val model = ExactLpModel(
        listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
        listOf(ExactLpNumber.of(-1L)),
        List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
        listOf(ExactLpRow()),
        ExactLpObjective(listOf(zero, zero)),
    )

    @Test
    fun `paused monolithic advice leaves basic coordinates unknown without another solve`() {
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            List(3) { listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))) },
            listOf(ExactLpNumber.of(-1L)),
            List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))) } +
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            listOf(ExactLpRow()), ExactLpObjective(List(3) { ExactLpNumber.of(it + 1L) } + zero),
        )
        val state = LpExactState(source)
        val solver = RevisedSimplex(assertNotNull(state.toWorkingModel()))
        solver.use {
            solver.openSolve(LpFloatAllowance(1_000_000L, 1), retainFundingStop = true).use { cursor ->
                assertIs<LpSolveStep.Paused>(cursor.advance())
                val work = solver.currentWork

                val guide = assertNotNull(solver.partialPrimalGuidance(state, Cancellation.Never))

                assertTrue(!guide.known[0])
                assertTrue(guide.known[1] && guide.known[2])
                assertTrue(guide.coordinates[1] == 0.0 && guide.coordinates[2] == 0.0)
                assertEquals(work, solver.currentWork)
                assertNull(solver.solvedExactState)
                assertNull(solver.partialPrimalGuidance(LpExactState(source), Cancellation.Never))
                assertNull(solver.partialPrimalGuidance(state, Cancellation { true }))
                assertTrue(solver.promoteSolve(LpFloatAllowance(1_000_000L, 10)))
                val completed = assertIs<LpSolveStep.Completed<FloatLpResult?>>(cursor.advance())
                assertEquals(1.0, assertNotNull(completed.value).primal[0])
            }
        }
        assertNull(solver.partialPrimalGuidance(state, Cancellation.Never))
    }

    @Test
    fun `scoped monolithic advice requires explicit admission and retains its pending solve`() {
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            List(3) { listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))) },
            listOf(ExactLpNumber.of(-1L)),
            List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))) } +
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            listOf(ExactLpRow()), ExactLpObjective(List(3) { ExactLpNumber.of(it + 1L) } + zero),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            assertNull(owner.advanceFloat(1_000_000L, LpFloatAllowance(1_000_000L, 1),
                retainFundingStop = true))
            val metrics = owner.lastMetrics
            assertTrue(owner.floatPaused)
            assertNull(owner.partialPrimalGuidance(Cancellation.Never))

            val guide = assertNotNull(owner.partialPrimalGuidance(Cancellation.Never, includeMonolithic = true))

            assertTrue(guide.known[1] && guide.known[2] && !guide.known[0])
            assertTrue(owner.floatPaused)
            assertEquals(metrics, owner.lastMetrics)
            assertNull(owner.lastResult)
        }
    }

    @Test
    fun `infeasible guidance should certify a ray without a point or a bound`() {
        LpScopedSolver(LpExactState(model)).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), ReconstructionLimits())

            assertEquals(LpFloatTermination.INFEASIBLE_CANDIDATE, guide.termination)
            assertEquals(LpRootGuidancePhase.COMPLETE, guide.phase)
            assertTrue(checkedLpConflict(assertNotNull(owner.state.ownerWorkingModel()), assertNotNull(guide.conflict)))
            assertNotNull(guide.conflictSupport)
            assertNull(guide.primal)
            assertNull(guide.bound)
            assertEquals(0, guide.certificateMetrics?.pointChecks)
            assertTrue(assertNotNull(guide.certificateMetrics).raySuccesses > 0)
        }
    }

    @Test
    fun `refused conflict certification should publish no refutation`() {
        val cases = listOf(
            ReconstructionLimits(maxWork = 0L) to LpSolveContext.Production,
            ReconstructionLimits() to LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false }),
        )
        for ((limits, context) in cases) {
            LpScopedSolver(LpExactState(model), context = context).use { owner ->
                val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), limits)

                assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
                assertNull(guide.conflict)
                assertNull(guide.conflictSupport)
                assertNull(guide.primal)
                assertNull(guide.bound)
            }
        }
    }

    @Test
    fun `owner cancellation during conflict certification should discard the conflict`() {
        var cancelled = false
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                cancelled = true
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }
        LpScopedSolver(LpExactState(model), Cancellation { cancelled }).use { owner ->
            val guide = owner.advanceRootGuidance(100_000L, LpFloatAllowance(100_000L, 100), ReconstructionLimits(),
                observer = observer)

            assertNull(guide.conflict)
            assertNull(guide.conflictSupport)
            assertEquals(LpRootGuidancePhase.CERTIFICATE, guide.phase)
        }
    }

    @Test
    fun `invalid and resource limited ray candidates should provide no conflict`() {
        val working = assertNotNull(LpExactState(model).toWorkingModel())
        val candidates = listOf(doubleArrayOf(Double.NaN), doubleArrayOf(0.0), doubleArrayOf())
        for (candidate in candidates) {
            assertNull(certifyLpConflictOnly(working, candidate, ReconstructionLimits()).conflict)
        }
        val allowances = listOf(
            ReconstructionLimits(maxWork = 0L) to ReconstructionDecline.WORK,
            ReconstructionLimits(maxAllocation = 0L) to ReconstructionDecline.ALLOCATION,
            ReconstructionLimits(maxCoordinates = 0) to ReconstructionDecline.DIMENSION,
        )
        for ((limits, decline) in allowances) {
            val checked = certifyLpConflictOnly(working, doubleArrayOf(-1.0), limits)

            assertNull(checked.conflict)
            assertEquals(decline, checked.metrics.decline)
            assertTrue(checked.metrics.work <= limits.maxWork)
            assertTrue(checked.metrics.allocation <= limits.maxAllocation)
        }
    }
}
