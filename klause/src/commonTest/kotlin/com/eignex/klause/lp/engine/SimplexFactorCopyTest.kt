package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimplexFactorCopyTest {
    @Test
    fun `ordinary logical columns and repaired unit rows retain their heading mapping`() {
        val matrix = SparseMatrix.ofColumns(1, 3, listOf(emptyList(), listOf(0 to 2.0), listOf(0 to 1.0)))
        for (repaired in listOf(false, true)) {
            KotlinBasisSolver(matrix).use { parent ->
                val repair = if (repaired) {
                    assertNotNull(parent.refactorizeRepairing(intArrayOf(0)))
                } else {
                    assertTrue(parent.refactorize(intArrayOf(2)))
                    null
                }
                val copy = SimplexFactorCopy(matrix, assertNotNull(parent.factorCopy), intArrayOf(2),
                    repair?.columns ?: intArrayOf(2), repair?.unitRows ?: intArrayOf(-1))
                val owner = SimplexBasisLifecycle(1, 3)
                owner.basicVar[0] = 2
                assertTrue(copy.install(owner, matrix, 2, 100L, 10_000L) { false })
                assertNull(owner.retire())
            }
        }
    }

    @Test
    fun `copied factors install only for their immutable matrix and normalized headings`() {
        val matrix = SparseMatrix.ofColumns(1, 2, listOf(listOf(0 to 2.0), listOf(0 to 1.0)))
        KotlinBasisSolver(matrix).use { parent ->
            assertTrue(parent.refactorize(intArrayOf(0)))
            val offer = assertNotNull(parent.factorCopy)
            for (mismatch in listOf("matrix", "headings", "column", "unit", "sentinel", "cancel", "storage")) {
                val owner = SimplexBasisLifecycle(1, 2)
                owner.basicVar[0] = if (mismatch == "headings") 1 else 0
                val copy = SimplexFactorCopy(matrix, offer, intArrayOf(0),
                    intArrayOf(if (mismatch == "column") 1 else 0),
                    intArrayOf(when (mismatch) { "unit" -> 0; "sentinel" -> -2; else -> -1 }))
                val target = if (mismatch == "matrix") {
                    SparseMatrix.ofColumns(1, 2, listOf(listOf(0 to 2.0), listOf(0 to 1.0)))
                } else matrix

                assertFalse(copy.install(owner, target, 1,
                    if (mismatch == "storage") offer.entries - 1L else offer.entries,
                    10_000L) { mismatch == "cancel" })

                assertNull(owner.current)
                assertFalse(owner.factorized)
            }
        }
    }

    @Test
    fun `installed factors own their normalized keys and outlive the producer`() {
        val matrix = SparseMatrix.ofColumns(1, 2, listOf(listOf(0 to 2.0), listOf(0 to 1.0)))
        val parent = KotlinBasisSolver(matrix)
        assertTrue(parent.refactorize(intArrayOf(0)))
        val headings = intArrayOf(0)
        val columns = intArrayOf(0)
        val units = intArrayOf(-1)
        val copy = SimplexFactorCopy(matrix, assertNotNull(parent.factorCopy), headings, columns, units)
        headings.fill(-1)
        columns.fill(-1)
        units.fill(0)
        val owner = SimplexBasisLifecycle(1, 2)
        owner.basicVar[0] = 0
        assertTrue(copy.install(owner, matrix, 1, 100L, 10_000L) { false })
        parent.close()
        try {
            val solver = assertNotNull(owner.current)
            val rhs = IndexedVector(1).also { it.store(0, 6.0) }
            solver.ftran(rhs)
            assertEquals(3.0, rhs[0])
            assertTrue(owner.factorized)
            assertEquals(0, owner.columns[0])
            assertEquals(-1, owner.unitRows[0])
            assertFalse(copy.install(owner, matrix, 1, 100L, 10_000L) { false })
        } finally {
            assertNull(owner.retire())
        }
    }
}
