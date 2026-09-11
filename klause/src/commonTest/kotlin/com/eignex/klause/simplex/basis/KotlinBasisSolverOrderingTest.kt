package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinBasisSolverOrderingTest {
    @Test
    fun `ordering snapshots own inputs and every returned array`() {
        val source = ftSource("dense", 3)
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(2, 0, 1)))
        val order = assertNotNull(solver.ordering())
        val rows = order.rows
        val slots = order.slots
        order.columns.fill(-99)
        order.unitRows.fill(-99)
        order.rows.fill(-99)
        order.slots.fill(-99)
        assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
        solver.close()

        assertContentEquals(intArrayOf(2, 0, 1), order.columns)
        assertContentEquals(intArrayOf(-1, -1, -1), order.unitRows)
        assertContentEquals(rows, order.rows)
        assertContentEquals(slots, order.slots)
        assertNull(solver.ordering())
        val inputs = intArrayOf(0)
        val copied = BasisOrdering(inputs, intArrayOf(-1), inputs, inputs)
        inputs[0] = 5
        assertContentEquals(intArrayOf(0), copied.rows)
        assertContentEquals(intArrayOf(0), copied.slots)
        assertContentEquals(intArrayOf(0), copied.columns)
    }

    @Test
    fun `accepted updates decline until refactorization while rejected updates preserve the offer`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source, updateLimit = 1).use { solver ->
            val headings = intArrayOf(0, 1, 2)
            assertNull(solver.ordering())
            assertTrue(solver.refactorize(headings))
            assertEquals(BasisUpdate.SINGULAR, solver.update(1, 4, IndexedVector(3)))
            assertNotNull(solver.ordering())
            val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
            solver.ftran(spike)

            assertEquals(BasisUpdate.REFACTORIZE, solver.update(1, 4, spike))
            assertNull(solver.ordering())
            headings[1] = 4
            assertTrue(solver.refactorize(headings))
            assertContentEquals(headings, assertNotNull(solver.ordering()).columns)
        }
    }

    @Test
    fun `restores recover the saved order eligibility and reject foreign snapshots`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { solver ->
            assertTrue(solver.refactorize(intArrayOf(2, 0, 1)))
            val initial = assertNotNull(solver.snapshot())
            val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
            solver.ftran(spike)
            assertEquals(BasisUpdate.APPLIED, solver.update(1, 4, spike))
            val updated = assertNotNull(solver.snapshot())
            assertTrue(solver.restore(initial))
            assertContentEquals(intArrayOf(2, 0, 1), assertNotNull(solver.ordering()).columns)
            assertTrue(solver.restore(updated))
            assertNull(solver.ordering())
            KotlinBasisSolver(source).use { foreign ->
                assertFalse(foreign.restore(initial))
                assertNull(foreign.ordering())
            }
            assertTrue(solver.restore(initial))
            initial.close()
            assertFalse(solver.restore(initial))
            assertNotNull(solver.ordering())
        }
    }

    @Test
    fun `repair exports accepted logical columns and failed rebuild retires the order`() {
        val source = SparseMatrix.ofColumns(3, 3, listOf(listOf(1 to 1.0), listOf(1 to 1.0), emptyList()))
        KotlinBasisSolver(source).use { solver ->
            val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 1, 2)))
            val order = assertNotNull(solver.ordering())
            assertTrue(repair.repaired)
            assertContentEquals(repair.columns, order.columns)
            assertContentEquals(repair.unitRows, order.unitRows)

            assertFalse(solver.refactorize(intArrayOf(0, 1, 2)))
            assertNull(solver.ordering())
        }
    }
}
