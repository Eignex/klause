package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class KotlinBasisSolverSnapshotTest {
    @Test
    fun `snapshots restore independent states before and after updates`() {
        val source = ftSource("dense", 5)
        val solver = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 100.0)
        val basis = IntArray(5) { it }
        assertTrue(solver.refactorize(basis))
        val initial = assertNotNull(solver.snapshot())
        replace(solver, source, basis, 1, 6)
        replace(solver, source, basis, 4, 9)
        assertBasisSolves(solver, source, basis, IntArray(5) { -1 })
        val updated = assertNotNull(solver.snapshot())
        val updatedWork = solver.lastUpdateWork
        val updatedSolveWork = solver.lastSolveWork
        val updatedBasisWork = solver.basisWork
        replace(solver, source, basis, 3, 8)

        assertTrue(solver.restore(initial))
        assertEquals(0, solver.updateCount)
        assertMatchesFresh(solver, source, IntArray(5) { it }, IntArray(5) { -1 })
        assertTrue(solver.restore(updated))
        assertEquals(2, solver.updateCount)
        assertEquals(updatedWork, solver.lastUpdateWork)
        assertEquals(updatedSolveWork, solver.lastSolveWork)
        assertEquals(updatedBasisWork, solver.basisWork)
        val restoredBasis = intArrayOf(0, 6, 2, 3, 9)
        assertBasisSolves(solver, source, restoredBasis, IntArray(5) { -1 })
        replace(solver, source, restoredBasis, 3, 8)
        assertEquals(3, solver.updateCount)
        assertMatchesFresh(solver, source, restoredBasis, IntArray(5) { -1 })
        assertTrue(solver.restore(updated))
        assertEquals(2, solver.updateCount)
        assertMatchesFresh(solver, source, intArrayOf(0, 6, 2, 3, 9), IntArray(5) { -1 })
    }

    @Test
    fun `restore work is determined by the saved snapshot payload`() {
        val source = ftSource("dense", 5)
        val solver = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 100.0)
        val basis = IntArray(5) { it }
        assertTrue(solver.refactorize(basis))
        replace(solver, source, basis, 1, 6)
        val snapshot = assertNotNull(solver.snapshot())
        val beforeFirst = solver.basisOperationWork.restore.units

        assertTrue(solver.restore(snapshot))
        val firstUnits = solver.basisOperationWork.restore.units - beforeFirst
        assertTrue(solver.refactorize(IntArray(5) { 4 - it }))
        val beforeSecond = solver.basisOperationWork.restore.units

        assertTrue(solver.restore(snapshot))
        val secondUnits = solver.basisOperationWork.restore.units - beforeSecond

        assertTrue(firstUnits > 0)
        assertEquals(firstUnits, secondUnits)
    }

    @Test
    fun `repaired snapshots restore source and logical headings after failure`() {
        val source = SparseMatrix.ofColumns(
            3,
            4,
            listOf(
                listOf(1 to 1.0),
                listOf(1 to 1.0),
                emptyList(),
                listOf(0 to 1.0, 1 to 1.0, 2 to 1.0),
            ),
        )
        val solver = KotlinBasisSolver(source)
        val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1, 2)))
        val columns = repair.columns.copyOf()
        val unitRows = repair.unitRows.copyOf()
        val logical = unitRows.indexOfFirst { it >= 0 }
        val spike = IndexedVector(3).also { it.scatterColumn(source, 3) }
        solver.ftran(spike)
        assertEquals(BasisUpdate.APPLIED, solver.update(logical, 3, spike))
        columns[logical] = 3
        unitRows[logical] = -1
        val snapshot = assertNotNull(solver.snapshot())
        assertFalse(solver.refactorize(intArrayOf(0, 1, 2)))
        assertTrue(solver.singular)
        assertNull(solver.snapshot())

        assertTrue(solver.restore(snapshot))

        assertFalse(solver.singular)
        assertEquals(1, solver.updateCount)
        assertBasisSolves(solver, source, columns, unitRows)
        val repeated = assertNotNull(solver.snapshot())
        assertTrue(solver.restore(repeated))
        assertMatchesFresh(solver, source, columns, unitRows)
    }

    @Test
    fun `incompatible and closed snapshots preserve the current state`() {
        val source = ftSource("sparse", 4)
        val first = KotlinBasisSolver(source)
        val second = KotlinBasisSolver(source)
        val smaller = KotlinBasisSolver(ftSource("sparse", 3))
        val basis = IntArray(4) { it }
        assertTrue(first.refactorize(basis))
        assertTrue(second.refactorize(basis.reversedArray()))
        assertTrue(smaller.refactorize(IntArray(3) { it }))
        val firstSnapshot = assertNotNull(first.snapshot())
        val secondSnapshot = assertNotNull(second.snapshot())

        assertFalse(second.restore(firstSnapshot))
        assertFalse(smaller.restore(firstSnapshot))
        assertMatchesFresh(second, source, basis.reversedArray(), IntArray(4) { -1 })
        firstSnapshot.close()
        firstSnapshot.close()
        assertFalse(first.restore(firstSnapshot))
        assertMatchesFresh(first, source, basis, IntArray(4) { -1 })
        assertFalse(
            first.restore(object : BasisSnapshot {
                override fun close() = Unit
            }),
        )
        assertMatchesFresh(first, source, basis, IntArray(4) { -1 })
        secondSnapshot.close()
    }

    @Test
    fun `solver close invalidates snapshots and remains idempotent`() {
        val source = ftSource("sparse", 3)
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(IntArray(3) { it }))
        val snapshot = assertNotNull(solver.snapshot())

        solver.close()
        solver.close()
        snapshot.close()

        assertFailsWith<IllegalStateException> { solver.restore(snapshot) }
        assertFailsWith<IllegalStateException> { solver.snapshot() }
    }

    @Test
    fun `declined solve work survives snapshots and resets on build`() {
        val source = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1e-200)))
        val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
        assertTrue(solver.refactorize(intArrayOf(0)))
        val vector = IndexedVector(1).also { it.store(0, 1e200) }
        assertFailsWith<BasisArithmeticException> { solver.ftran(vector) }
        val declined = solver.basisWork
        assertEquals(1, declined.ftran.declines)
        assertTrue(declined.ftran.units > 0)
        val snapshot = assertNotNull(solver.snapshot())
        vector.scatter(doubleArrayOf(1e-200))
        solver.ftran(vector)

        assertTrue(solver.restore(snapshot))

        assertEquals(declined, solver.basisWork)
        assertTrue(solver.refactorize(intArrayOf(0)))
        assertEquals(BasisPhaseWork(), solver.basisWork.ftran)
    }

    @Test
    fun `declined update work survives snapshots and resets on build`() {
        val source = SparseMatrix.ofColumns(
            2,
            3,
            listOf(
                listOf(0 to 1.0),
                listOf(0 to 1e200, 1 to 1e-200),
                emptyList(),
            ),
        )
        val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val spike = IndexedVector(2).also { it.scatter(doubleArrayOf(1.0, 1.0)) }
        assertEquals(BasisUpdate.SINGULAR, solver.update(0, 2, spike))
        val declined = solver.basisWork
        assertEquals(1, declined.update.declines)
        assertTrue(declined.update.units > 1 + spike.count)
        assertTrue(assertNotNull(solver.lastUpdateWork).units > 0)
        val snapshot = assertNotNull(solver.snapshot())
        assertEquals(BasisUpdate.SINGULAR, solver.update(0, 2, spike))
        assertTrue(solver.basisWork.update.attempts > declined.update.attempts)

        assertTrue(solver.restore(snapshot))

        assertEquals(declined, solver.basisWork)
        assertTrue(assertNotNull(solver.lastUpdateWork).units > 0)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        assertEquals(BasisPhaseWork(), solver.basisWork.update)
    }

    @Test
    fun `bounded snapshot restore and fresh build operations are measured separately`() {
        val source = ftSource("dense", 8)
        val basis = IntArray(8) { it }
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(basis))
        val requested = basis.copyOf().also { it[it.lastIndex] = it[it.lastIndex - 1] }
        val repairProbe = KotlinBasisSolver(source)
        val repaired = assertNotNull(repairProbe.refactorizeRepairing(requested))
        val augmented = withLogicalColumns(source)
        val repairedHeadings = IntArray(8) {
            if (repaired.columns[it] >= 0) repaired.columns[it] else source.cols + repaired.unitRows[it]
        }
        var snapshotNanos = 0L
        var restoreNanos = 0L
        var repairNanos = 0L
        var freshNanos = 0L
        repeat(8) {
            var mark = TimeSource.Monotonic.markNow()
            val snapshot = assertNotNull(solver.snapshot())
            snapshotNanos += mark.elapsedNow().inWholeNanoseconds
            mark = TimeSource.Monotonic.markNow()
            assertTrue(solver.restore(snapshot))
            restoreNanos += mark.elapsedNow().inWholeNanoseconds
            snapshot.close()
            val repair = KotlinBasisSolver(source)
            mark = TimeSource.Monotonic.markNow()
            assertNotNull(repair.refactorizeRepairing(requested))
            repairNanos += mark.elapsedNow().inWholeNanoseconds
            repair.close()
            val fresh = KotlinBasisSolver(augmented)
            mark = TimeSource.Monotonic.markNow()
            assertTrue(fresh.refactorize(repairedHeadings))
            freshNanos += mark.elapsedNow().inWholeNanoseconds
            fresh.close()
        }
        repairProbe.close()
        println(
            "B4a bounded snapshotNanos=$snapshotNanos restoreNanos=$restoreNanos " +
                "repairNanos=$repairNanos freshBuildNanos=$freshNanos",
        )
    }

    private fun replace(solver: KotlinBasisSolver, source: SparseMatrix, basis: IntArray, slot: Int, entering: Int) {
        val spike = IndexedVector(source.rows).also { it.scatterColumn(source, entering) }
        solver.ftran(spike, 0.0)
        assertEquals(BasisUpdate.APPLIED, solver.update(slot, entering, spike, spike))
        basis[slot] = entering
    }
}
