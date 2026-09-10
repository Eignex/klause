package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSnapshot
import com.eignex.klause.simplex.basis.BasisSolveQuality
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RevisedSimplexSnapshotTest {
    @Test
    fun `objective adoption follows zero nonzero zero revisions`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(ExactLpNumber.of(-3L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(source)
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            assertEquals(BigFraction.ZERO, solveCurrent(solver, trail))

            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(minusOne, zero))))
            assertEquals(BigFraction.ofLong(-10L), solveCurrent(solver, trail))

            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(zero, zero))))
            assertEquals(BigFraction.ZERO, solveCurrent(solver, trail))
        }
    }

    @Test
    fun `factor restart restores on its owner and solves the current objective`() {
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        val trail = LpBoundTrail(model(zero))
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            solveCurrent(solver, trail)
            val snapshot = assertNotNull(solver.captureBasisRestart())
            val beforeRestore = assertNotNull(solver.basisLifecycleWork).units

            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(minusOne, zero))))
            assertEquals(BigFraction.ofLong(-10L), solveCurrent(solver, trail))
            assertTrue(solver.restoreBasisRestart(snapshot))
            assertEquals(BigFraction.ofLong(-10L), solveCurrent(solver, trail))
            assertTrue(assertNotNull(solver.basisLifecycleWork).units > beforeRestore)

            snapshot.close()
            assertTrue(!solver.restoreBasisRestart(snapshot))
        }
    }

    @Test
    fun `status only restart refactorizes and rejects a foreign owner`() {
        val zero = ExactLpNumber.of(0L)
        val trail = LpBoundTrail(model(zero))
        val factory: (com.eignex.koblas.SparseMatrix) -> BasisSolver = { matrix ->
            StatusOnlySnapshotSolver(KotlinBasisSolver(matrix))
        }
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel()), basisSolverFactory = factory).use { first ->
            RevisedSimplex(assertNotNull(trail.state.toWorkingModel()), basisSolverFactory = factory).use { second ->
                solveCurrent(first, trail)
                solveCurrent(second, trail)
                val snapshot = assertNotNull(first.captureBasisRestart())

                assertTrue(!second.restoreBasisRestart(snapshot))
                assertTrue(first.restoreBasisRestart(snapshot))
                assertEquals(BigFraction.ZERO, solveCurrent(first, trail))
            }
        }
    }

    @Test
    fun `persistent bad residual requests one rebuild per unchanged basis`() {
        val trail = LpBoundTrail(model(ExactLpNumber.of(1L)))
        RevisedSimplex(
            assertNotNull(trail.state.toWorkingModel()),
            basisSolverFactory = { matrix -> BadQualitySolver(KotlinBasisSolver(matrix)) },
        ).use { solver ->
            repeat(24) { assertEquals(BigFraction.ofLong(3L), solveCurrent(solver, trail)) }

            val metrics = solver.lastRefactorPolicyMetrics
            assertTrue(metrics.qualitySamples >= 2L)
            assertEquals(1L, metrics.residualTriggers)
            assertTrue(metrics.cooldownDeclines >= 1L)
        }
    }

    @Test
    fun `restart cleanup unregisters closed handles and completes after failures`() {
        val trail = LpBoundTrail(model(ExactLpNumber.of(1L)))
        val tracker = SnapshotCleanupTracker()
        val solver = RevisedSimplex(
            assertNotNull(trail.state.toWorkingModel()),
            basisSolverFactory = { matrix -> SnapshotCleanupSolver(KotlinBasisSolver(matrix), tracker) },
        )
        solveCurrent(solver, trail)
        val first = assertNotNull(solver.captureBasisRestart())
        assertNotNull(solver.captureBasisRestart())
        assertNotNull(solver.captureBasisRestart())
        assertEquals(3, solver.liveBasisRestartSnapshots)

        first.close()
        assertEquals(2, solver.liveBasisRestartSnapshots)
        val failure = assertFailsWith<IllegalStateException> { solver.close() }

        assertEquals("snapshot 2", failure.message)
        assertEquals(listOf("snapshot 3", "owner"), failure.suppressedExceptions.map { it.message })
        assertEquals(3, tracker.snapshotCloses)
        assertEquals(1, tracker.ownerCloses)
        assertEquals(0, solver.liveBasisRestartSnapshots)
        solver.close()
        assertEquals(1, tracker.ownerCloses)
    }

    @Test
    fun `throwing cleanup after cancelled factor restore invalidates the retained basis`() {
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        val trail = LpBoundTrail(model(zero))
        val tracker = RestoreCleanupTracker()
        RevisedSimplex(
            assertNotNull(trail.state.toWorkingModel()),
            basisSolverFactory = { matrix -> RestoreCleanupSolver(KotlinBasisSolver(matrix), tracker) },
        ).use { solver ->
            assertEquals(BigFraction.ZERO, solveCurrent(solver, trail))
            val snapshot = assertNotNull(solver.captureBasisRestart())
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(minusOne, zero))))
            assertEquals(BigFraction.ofLong(-10L), solveCurrent(solver, trail))
            var cancellationPolls = 0

            val failure = assertFailsWith<IllegalStateException> {
                solver.restoreBasisRestart(snapshot) { ++cancellationPolls >= 3 }
            }

            assertEquals("snapshot cleanup", failure.message)
            assertEquals(1, tracker.restores)
            assertEquals(1, tracker.closes)
            assertEquals(0, solver.liveBasisRestartSnapshots)
            assertTrue(!solver.appendTransferReady)
            assertEquals(BigFraction.ofLong(-10L), solveCurrent(solver, trail))
        }
    }

    private fun model(cost: ExactLpNumber): ExactLpModel {
        val zero = ExactLpNumber.of(0L)
        val minusOne = ExactLpNumber.of(-1L)
        return ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(ExactLpNumber.of(-3L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(cost, zero)),
        )
    }

    private fun solveCurrent(solver: RevisedSimplex, trail: LpBoundTrail): BigFraction {
        assertTrue(solver.adopt(trail.state, Cancellation.Never))
        val result = assertNotNull(solver.resolveBounds())
        val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        return assertNotNull(certified.lowerBound)
    }
}

private class StatusOnlySnapshotSolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    override fun snapshot(): BasisSnapshot? = null
}

private class BadQualitySolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    override fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean): BasisSolveQuality =
        BasisSolveQuality(1e-4, 1e-4)
}

private class SnapshotCleanupTracker {
    var snapshots = 0
    var snapshotCloses = 0
    var ownerCloses = 0
}

private class SnapshotCleanupSolver(private val delegate: BasisSolver, private val tracker: SnapshotCleanupTracker) :
    BasisSolver by delegate {
    override fun snapshot(): BasisSnapshot {
        val id = ++tracker.snapshots
        return object : BasisSnapshot {
            override fun close() {
                tracker.snapshotCloses++
                if (id > 1) throw IllegalStateException("snapshot $id")
            }
        }
    }

    override fun close() {
        tracker.ownerCloses++
        delegate.close()
        throw IllegalStateException("owner")
    }
}

private class RestoreCleanupTracker {
    var restores = 0
    var closes = 0
}

private class RestoreCleanupSnapshot(val delegate: BasisSnapshot, private val tracker: RestoreCleanupTracker) :
    BasisSnapshot {
    override fun close() {
        tracker.closes++
        delegate.close()
        throw IllegalStateException("snapshot cleanup")
    }
}

private class RestoreCleanupSolver(private val delegate: BasisSolver, private val tracker: RestoreCleanupTracker) :
    BasisSolver by delegate {
    override fun snapshot(): BasisSnapshot? =
        delegate.snapshot()?.let { RestoreCleanupSnapshot(it, tracker) }

    override fun restore(snapshot: BasisSnapshot): Boolean {
        tracker.restores++
        return delegate.restore((snapshot as RestoreCleanupSnapshot).delegate)
    }
}
