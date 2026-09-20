package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BasisWorkTest {
    @Test
    fun `repair keeps kernel history outside the final active factorization phases`() {
        val source = ftSource("sparse", 5)
        KotlinBasisSolver(source, updateLimit = 1).use { solver ->
            val control = BasisRepairControl()

            assertNotNull(solver.refactorizeRepairing(IntArray(5) { it }, control))

            val active = solver.basisWork
            val lifetime = solver.basisOperationWork
            assertEquals(BasisPhaseWork(), active.ftran)
            assertEquals(BasisPhaseWork(), active.update)
            assertEquals(0, solver.updateCount)
            assertTrue(lifetime.ftran.successes > 0)
            assertTrue(lifetime.update.successes > 0)
            assertTrue(assertNotNull(active.build).builds > 2)
            assertEquals(control.spentWork, lifetime.units)
        }
    }

    @Test
    fun `each operation phase preserves saturation independently of unit totals`() {
        for (kind in BasisOperationKind.entries) {
            val meter = BasisOperationMeter()
            meter.success(kind, Long.MAX_VALUE)
            val report = meter.snapshot()

            assertEquals(Long.MAX_VALUE, report.units)
            assertTrue(report.saturated)
        }
        for (phase in listOf(
            BasisPhaseWork(attempts = Long.MAX_VALUE, declines = 0),
            BasisPhaseWork(successes = Long.MAX_VALUE, declines = 0),
            BasisPhaseWork(declines = Long.MAX_VALUE),
        )) {
            assertTrue(BasisOperationWork(update = phase).saturated)
        }
    }

    @Test
    fun `repeated traces report identical phase work`() {
        val source = SparseMatrix.ofColumns(
            2,
            3,
            listOf(
                listOf(0 to 1.0),
                listOf(1 to 1.0),
                listOf(0 to 1.0, 1 to 1.0),
            ),
        )

        val first = measuredTrace(source)
        val second = measuredTrace(source)

        assertEquals(first, second)
        assertEquals(2, first.ftran.attempts)
        assertEquals(2, first.ftran.successes)
        assertEquals(1, first.btran.attempts)
        assertEquals(1, first.btran.successes)
        assertEquals(2, first.update.attempts)
        assertEquals(1, first.update.successes)
        assertEquals(1, first.update.declines)
        assertTrue(first.workSinceBuild > 0)
    }

    @Test
    fun `successful reuse lowers deterministic build work and resets phase totals`() {
        val source = ftSource("dense", 16)
        val basis = IntArray(16) { 15 - it }
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(basis))
        val fresh = assertNotNull(solver.basisWork.build)
        val rhs = IndexedVector(16).also { it.unit(3) }
        solver.ftran(rhs, 0.0)
        assertTrue(solver.basisWork.workSinceBuild > 0)

        assertTrue(solver.refactorize(basis))

        val reused = assertNotNull(solver.basisWork.build)
        assertEquals(1, reused.orderingAttempts)
        assertEquals(1, reused.reusedOrders)
        assertEquals(0, reused.fallbacks)
        assertEquals(reused.units, reused.installedBuildUnits)
        assertTrue(reused.units * 100 <= fresh.units * 95, "fresh=$fresh reused=$reused")
        assertEquals(0, solver.basisWork.workSinceBuild)
    }

    @Test
    fun `fallback work is charged and a failed build keeps only a reusable hint`() {
        val source = SparseMatrix.ofColumns(
            2,
            4,
            listOf(
                listOf(0 to 1.0),
                listOf(1 to 1.0),
                listOf(1 to 1.0),
                listOf(0 to 1.0, 1 to 1.0),
            ),
        )
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))

        assertTrue(solver.refactorize(intArrayOf(2, 3)))
        val fallback = assertNotNull(solver.basisWork.build)
        assertEquals(1, fallback.orderingAttempts)
        assertEquals(0, fallback.reusedOrders)
        assertEquals(1, fallback.fallbacks)
        assertTrue(fallback.units > assertNotNull(fallback.installedBuildUnits) / 2)

        assertTrue(!solver.refactorize(intArrayOf(0, 0)))
        val failed = assertNotNull(solver.basisWork.build)
        assertTrue(!failed.successful)
        assertEquals(null, failed.installedBuildUnits)
        assertEquals(1, failed.fallbacks)
        assertTrue(solver.singular)

        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val recovered = assertNotNull(solver.basisWork.build)
        assertEquals(1, recovered.orderingAttempts)
        assertTrue(recovered.successful)
    }

    @Test
    fun `repair and close preserve work lifetimes`() {
        val source = ftSource("sparse", 5)
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(IntArray(5) { it }))
        val requested = intArrayOf(0, 0, 2, 3, 4)
        assertNotNull(solver.refactorizeRepairing(requested))
        val repaired = solver.basisWork
        val repairedBuild = assertNotNull(repaired.build)
        assertEquals(BasisBuildKind.REPAIR, repairedBuild.kind)
        assertTrue(repairedBuild.builds > 1)
        assertTrue(repairedBuild.units >= assertNotNull(repairedBuild.installedBuildUnits))
        solver.close()
        assertFailsWith<IllegalStateException> { solver.basisWork }
    }

    @Test
    fun `disabled reuse keeps every repair trial on fresh ordering`() {
        val solver = KotlinBasisSolver(ftSource("sparse", 5), reusePivotOrder = false)

        assertNotNull(solver.refactorizeRepairing(IntArray(5) { it }))

        val build = assertNotNull(solver.basisWork.build)
        assertTrue(build.builds > 1)
        assertEquals(0, build.orderingAttempts)
        assertEquals(0, build.reusedOrders)
    }

    @Test
    fun `saturated phase counters preserve recorded declines`() {
        val full = BasisPhaseWork(attempts = Long.MAX_VALUE, successes = Long.MAX_VALUE - 1, declines = 1)

        val phase = full.mergedWith(BasisPhaseWork(attempts = 2, successes = 1, declines = 1))

        assertEquals(Long.MAX_VALUE, phase.attempts)
        assertEquals(Long.MAX_VALUE, phase.successes)
        assertEquals(2, phase.declines)
    }

    @Test
    fun `aggregate operation work reports saturation across finite phases`() {
        val work = BasisOperationWork(
            refactorization = BasisPhaseWork(units = Long.MAX_VALUE - 1),
            repair = BasisPhaseWork(units = 2),
        )

        assertEquals(Long.MAX_VALUE, work.units)
        assertTrue(work.saturated)
    }

    @Test
    fun `zero dimension reuses an empty order with zero work`() {
        val source = SparseMatrix.ofColumns(0, 0, emptyList())
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(IntArray(0)))
        assertEquals(0, assertNotNull(solver.basisWork.build).units)

        assertTrue(solver.refactorize(IntArray(0)))
        val build = assertNotNull(solver.basisWork.build)
        assertEquals(1, build.reusedOrders)
        assertEquals(0, build.units)
        solver.ftran(IndexedVector(0), 0.0)
        solver.btran(IndexedVector(0), 0.0)
        assertEquals(0, solver.basisWork.workSinceBuild)
    }

    @Test
    fun `operation work retains prior solves after a fresh factorization`() {
        val source = ftSource("dense", 5)
        KotlinBasisSolver(source).use { solver ->
            assertTrue(solver.refactorize(IntArray(5) { it }))
            solver.ftran(IndexedVector(5).also { it.unit(0) }, 0.0)
            val before = solver.basisOperationWork

            assertTrue(solver.refactorize(IntArray(5) { it }))

            val after = solver.basisOperationWork
            assertTrue(after.units > before.units)
            assertEquals(2, after.refactorization.successes)
            assertEquals(before.ftran, after.ftran)
            assertEquals(0, solver.basisWork.workSinceBuild)
        }
    }

    private fun measuredTrace(source: SparseMatrix): BasisWork {
        val solver = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 100.0)
        val basis = IntArray(source.rows) { it }
        assertTrue(solver.refactorize(basis))
        val rhs = IndexedVector(source.rows).also { it.unit(0) }
        solver.ftran(rhs, 0.0)
        rhs.unit(1)
        solver.btran(rhs, 0.0)
        val spike = IndexedVector(source.rows).also { it.scatterColumn(source, source.rows) }
        solver.ftran(spike, 0.0)
        assertEquals(BasisUpdate.APPLIED, solver.update(0, source.rows, spike))
        assertEquals(BasisUpdate.SINGULAR, solver.update(0, 0, IndexedVector(source.rows)))
        return solver.basisWork
    }
}
