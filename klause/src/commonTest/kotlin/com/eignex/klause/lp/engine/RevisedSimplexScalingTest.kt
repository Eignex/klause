package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexScalingTest {

    @Test
    fun `the first solve reports building its scaling view as preparation`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))),
                listOf(ExactLpEntry(0, ExactLpNumber.of(-1_000_000L))),
            ),
            listOf(ExactLpNumber.of(-3_000_000L)),
            List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            listOf(ExactLpRow()),
            ExactLpObjective(List(3) { zero }),
        )
        val solver = RevisedSimplex(assertNotNull(LpExactState(source).toWorkingModel()))

        assertNotNull(solver.prepareLogicals(Cancellation.Never))
        val first = solver.lastMetrics
        assertNotNull(solver.prepareLogicals(Cancellation.Never))
        val second = solver.lastMetrics

        assertTrue(solver.scalingMetrics.work > 0L)
        assertEquals(solver.scalingMetrics.work, first.preparationOps)
        assertEquals(0L, second.preparationOps)
        assertEquals(first.workOps, second.workOps)
    }

    @Test
    fun `scaled exact state is certified against unscaled authority`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.of(-1L))),
                listOf(ExactLpEntry(0, ExactLpNumber.of(-1_000_000L))),
            ),
            listOf(ExactLpNumber.of(-3_000_000L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, ExactLpNumber.of(2L), zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val solver = RevisedSimplex(model)

        val result = assertNotNull(solver.solve())
        val certified = certifyLpResult(model, solver, result)

        assertTrue(solver.scalingMetrics.applied)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        assertEquals(listOf(BigFraction.ZERO, BigFraction.ofLong(3L)), certified.exactPrimal)
        assertEquals(BigFraction.ofLong(6L), certified.lowerBound)
    }

    @Test
    fun `integer tableau cuts remain available from a scaled basis`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1_000_000L), Relation.LE, 1_500_000L)
        val solver = RevisedSimplex(builder.build(Sense.MAXIMIZE))

        val result = assertNotNull(solver.solve())

        assertTrue(solver.scalingMetrics.applied)
        assertEquals(1.5, result.primal[x], 1e-9)
        assertTrue(solver.gomoryCuts(1).isNotEmpty())
    }

    @Test
    fun `scaled optimum exposes primal dual objective and residuals in source units`() {
        val model = mixedRealModel()
        val scaledSolver = RevisedSimplex(model)
        val unscaledSolver = RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false))

        val scaled = assertNotNull(scaledSolver.solve())
        val unscaled = assertNotNull(unscaledSolver.solve())

        assertTrue(scaledSolver.scalingMetrics.applied)
        assertEquals(unscaled.objective, scaled.objective, 1e-8)
        assertEquals(unscaled.primal[0], scaled.primal[0], 1e-8)
        assertEquals(unscaled.primal[1], scaled.primal[1], 1e-8)
        assertEquals(unscaled.duals[0], scaled.duals[0], 1e-12)
        assertTrue(scaled.primal[0] >= 5.0)
        assertTrue(scaled.primal[1] >= 0.0)
        assertTrue(1e-6 * scaled.primal[0] + 1e6 * scaled.primal[1] >= 2_000_000.0 - 1e-7)
        assertTrue(scaledSolver.scalingMetrics.sourcePrimalResidual <= 1e-7)
        assertTrue(scaledSolver.scalingMetrics.sourceBoundViolation <= 1e-7)
        assertTrue(scaledSolver.scalingMetrics.sourceBasicDualResidual <= 1e-12)
    }

    @Test
    fun `scaled dual infeasibility ray certifies the source model`() {
        val model = infeasibleRealModel()
        val solver = RevisedSimplex(model)

        val result = solver.solve()

        assertNull(result)
        assertTrue(solver.scalingMetrics.applied)
        assertNotNull(solver.infeasibleRay)
        assertNotNull(
            integerFarkasRay(
                model,
                assertNotNull(solver.infeasibleRay),
                basis = solver.infeasibleBasis,
                basisRow = solver.infeasibleRow,
            ),
        )
    }

    @Test
    fun `bound adoption retains the scaled factors`() {
        val model = mixedIntegerModel()
        val initial = LpExactState(assertNotNull(model.authoritativeModel()))
        val solver = RevisedSimplex(assertNotNull(initial.toWorkingModel()))
        assertNotNull(solver.solve())
        assertTrue(solver.scalingMetrics.applied)
        val version = solver.scaleVersion
        val next = model.rebind(longArrayOf(2L, 0L), longArrayOf(10L, 10L))

        assertTrue(
            solver.adopt(
                LpExactState(assertNotNull(next.authoritativeModel()), boundRevision = 1L),
                Cancellation.Never,
            ),
        )
        val reused = assertNotNull(solver.resolveBounds())
        val fresh = assertNotNull(RevisedSimplex(next).solve())

        assertTrue(reused.warmStarted)
        assertEquals(0, reused.refactorizations)
        assertEquals(fresh.objective, reused.objective, 1e-8)
        assertTrue(solver.scalingMetrics.applied)
        assertEquals(version, solver.scaleVersion)
    }

    @Test
    fun `unsafe objective refresh retires scaled factors and solves unscaled`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1e-300))),
                listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1e300))),
            ),
            listOf(ExactLpNumber.of(0L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero, zero)),
        )
        val trail = LpBoundTrail(source)
        val solver = RevisedSimplex(assertNotNull(trail.state.toWorkingModel()))
        assertNotNull(solver.solve())
        assertTrue(solver.scalingMetrics.applied)
        val scaledVersion = solver.scaleVersion
        val changed = ExactLpObjective(listOf(ExactLpNumber.ofIeee(1e300), zero, zero))
        assertTrue(trail.replaceObjective(changed))

        assertTrue(solver.adopt(trail.state, Cancellation.Never))
        val result = assertNotNull(solver.resolveBounds())

        assertFalse(solver.scalingMetrics.applied)
        assertEquals(LpScalingDecline.UPDATE_UNSAFE, solver.scalingMetrics.decline)
        assertEquals(1, solver.scalingMetrics.fallbacks)
        assertTrue(scaledVersion != solver.scaleVersion)
        assertFalse(result.warmStarted)
        assertTrue(result.refactorizations > 0)
        assertEquals(0.0, result.objective, 0.0)
    }

    @Test
    fun `projection losing bound refresh falls back atomically`() {
        val trail = LpBoundTrail(exactScaledModel())
        val solver = RevisedSimplex(assertNotNull(trail.state.toWorkingModel()))
        assertNotNull(solver.solve())
        assertTrue(solver.scalingMetrics.applied)
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 2000))

        assertTrue(trail.assertBound(0, false, ExactLpSide(tiny), 7L))
        assertTrue(solver.adopt(trail.state, Cancellation.Never))

        assertFalse(solver.scalingMetrics.applied)
        assertEquals(LpScalingDecline.UPDATE_UNSAFE, solver.scalingMetrics.decline)
        assertEquals(1, solver.scalingMetrics.fallbacks)
        assertTrue(solver.adopt(trail.state, Cancellation.Never))
        assertEquals(1, solver.scalingMetrics.fallbacks)
    }

    @Test
    fun `fallback cleanup retires the owner after failure`() {
        val trail = LpBoundTrail(exactScaledModel())
        val tracker = FallbackCleanupTracker()
        val solver = RevisedSimplex(
            assertNotNull(trail.state.toWorkingModel()),
            basisSolverFactory = { matrix -> FallbackCleanupSolver(KotlinBasisSolver(matrix), tracker) },
        )
        assertNotNull(solver.solve())
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 2000))
        assertTrue(trail.assertBound(0, false, ExactLpSide(tiny), 7L))

        val failure = assertFailsWith<IllegalStateException> {
            solver.adopt(trail.state, Cancellation.Never)
        }

        assertEquals("owner", failure.message)
        assertTrue(failure.suppressedExceptions.isEmpty())
        assertEquals(1, tracker.ownerCloses)
        assertFalse(solver.scalingMetrics.applied)
        assertEquals(1, solver.scalingMetrics.fallbacks)
        solver.close()
        assertEquals(1, tracker.ownerCloses)
    }

    @Test
    fun `unscaled retry retains scaled attempt work and refactorizations`() {
        val model = mixedRealModel()
        var owners = 0
        val solver = RevisedSimplex(
            model,
            basisSolverFactory = { matrix ->
                owners++
                val delegate = KotlinBasisSolver(matrix)
                if (owners == 1) {
                    object : BasisSolver by delegate {
                        override fun ftran(x: IndexedVector, expectedDensity: Double) {
                            delegate.ftran(x, expectedDensity)
                            throw BasisArithmeticException("injected scaled failure")
                        }
                    }
                } else {
                    delegate
                }
            },
        )
        val unscaled = RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false))

        val result = assertNotNull(solver.solve())
        assertNotNull(unscaled.solve())

        assertEquals(2, owners)
        assertFalse(solver.scalingMetrics.applied)
        assertEquals(1, solver.scalingMetrics.fallbacks)
        assertTrue(result.refactorizations >= 2)
        assertTrue(solver.lastWorkOps > unscaled.lastWorkOps)
    }

    private fun mixedRealModel(): LpModel {
        val builder = LpBuilder()
        val x = builder.addRealVar(5.0, 15.0, cost = 3.0)
        val y = builder.addRealVar(0.0, 4.0, cost = 2.0)
        builder.addRealRow(intArrayOf(x, y), doubleArrayOf(1e-6, 1e6), Relation.GE, 2_000_000.0)
        return builder.build(Sense.MINIMIZE)
    }

    private fun infeasibleRealModel(): LpModel {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1e-6), Relation.GE, 2e-6)
        return builder.build(Sense.MINIMIZE)
    }

    private fun mixedIntegerModel(): LpModel {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 10L, cost = 1L)
        val y = builder.addVar(0L, 10L, cost = 2L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1_000_000L), Relation.GE, 3_000_000L)
        return builder.build(Sense.MINIMIZE)
    }

    private fun exactScaledModel(): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        return ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, one)),
                listOf(ExactLpEntry(0, ExactLpNumber.of(1_000_000L))),
            ),
            listOf(ExactLpNumber.of(3_000_000L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, ExactLpNumber.of(2L), zero)),
        )
    }
}

private class FallbackCleanupTracker {
    var ownerCloses = 0
}

private class FallbackCleanupSolver(private val delegate: BasisSolver, private val tracker: FallbackCleanupTracker) :
    BasisSolver by delegate {
    override fun close() {
        tracker.ownerCloses++
        delegate.close()
        throw IllegalStateException("owner")
    }
}
