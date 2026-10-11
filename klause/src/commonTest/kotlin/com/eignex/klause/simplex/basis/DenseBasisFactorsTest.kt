package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DenseBasisFactorsTest {
    @Test
    fun `dimension boundary and off control retain sparse selection`() {
        for (n in 2..7) {
            val source = ftSource("dense", n)
            for (limit in listOf(0, 6)) {
                KotlinBasisSolver(source, denseDimensionLimit = limit).use { solver ->
                    assertTrue(solver.refactorize(IntArray(n) { n - 1 - it }))

                    val expected = if (limit > 0 && n <= limit) 1L else 0L
                    assertEquals(expected, assertNotNull(solver.basisWork.build).denseAttempts, "n=$n limit=$limit")
                }
            }
        }
    }

    @Test
    fun `singleton bases retain sparse construction below the dimension limit`() {
        for (n in 1..6) {
            val source = ftSource("unit", n)
            KotlinBasisSolver(source).use { solver ->
                assertTrue(solver.refactorize(IntArray(n) { n - 1 - it }))

                assertEquals(0L, assertNotNull(solver.basisWork.build).denseAttempts)
            }
        }
    }

    @Test
    fun `density boundary ignores explicit zeros in selected columns`() {
        for (entries in listOf(17, 18, 19)) {
            val source = SparseMatrix.ofColumns(6, 6, List(6) { j ->
                List(6) { i ->
                    val distance = (i - j + 6) % 6
                    i to when {
                        distance == 0 -> 6.0
                        distance == 1 -> 1.0
                        distance == 2 && j < entries - 12 -> 1.0
                        else -> 0.0
                    }
                }
            })
            KotlinBasisSolver(source).use { solver ->
                assertTrue(solver.refactorize(IntArray(6) { it }))

                assertEquals(if (entries >= 18) 1L else 0L, assertNotNull(solver.basisWork.build).denseAttempts)
            }
        }
    }

    @Test
    fun `dense factors preserve both solve directions across updates and rebuilds`() {
        for (n in 2..6) {
            val source = ftSource("dense", n)
            val headings = IntArray(n) { n - 1 - it }
            KotlinBasisSolver(source, updateLimit = 1).use { solver ->
                assertTrue(solver.refactorize(headings))
                val spike = IndexedVector(n).also { it.scatterColumn(source, n + 1) }
                solver.ftran(spike)
                val slot = (0 until n).maxBy { kotlin.math.abs(spike[it]) }
                assertEquals(BasisUpdate.REFACTORIZE, solver.update(slot, n + 1, spike))
                headings[slot] = n + 1
                assertNull(solver.ordering())
                assertEquals(BasisUpdate.SINGULAR, solver.update(slot, n, IndexedVector(n)))
                for (rebuild in listOf(false, true)) {
                    if (rebuild) assertTrue(solver.refactorize(headings))
                    for (transpose in listOf(false, true)) {
                        val rhs = DoubleArray(n) { if (it % 2 == 0) (it + 1).toDouble() else -(it + 1).toDouble() }
                        val solution = IndexedVector(n).also { it.scatter(rhs) }
                        if (transpose) solver.btran(solution) else solver.ftran(solution)

                        assertTrue(ftResidual(source, headings, rhs, solution, transpose) < 1e-12)
                        assertTrue(solver.solveQuality(rhs, solution, transpose).relativeResidual < 1e-12)
                    }
                }
                assertNotNull(solver.ordering())
                assertTrue(solver.basisOperationWork.complete)
            }
        }
    }

    @Test
    fun `dense construction rejects overflowing and underflowing arithmetic`() {
        val matrices = listOf(
            arrayOf(doubleArrayOf(1e308, 1e308), doubleArrayOf(1e308, -1e308)),
            arrayOf(doubleArrayOf(1e200, 1e-200), doubleArrayOf(1e-200, 2e-200)),
            arrayOf(doubleArrayOf(1.0, 1e-200), doubleArrayOf(1e-200, 1e-200)),
        )
        for (matrix in matrices) {
            val source = SparseMatrix.ofColumns(2, 2, List(2) { j -> List(2) { i -> i to matrix[i][j] } })
            val builder = DenseBasisFactors(source)
            val policy = LuPivotPolicy(absoluteTolerance = 0.0)

            val result = assertIs<LuBuildResult.Rejected>(builder.build(intArrayOf(0, 1), policy))

            assertEquals(LuBuildRejection.ARITHMETIC_BREAKDOWN, result.reason)
            assertEquals(result.work, builder.build(intArrayOf(0, 1), policy).work)
        }
    }

    @Test
    fun `dense arithmetic decline retries sparse pivoting and retains failed work`() {
        val source = SparseMatrix.ofColumns(2, 2, listOf(listOf(0 to 1.0, 1 to 1.0), listOf(0 to 1e308, 1 to -1e308)))
        val headings = intArrayOf(0, 1)
        val dense = DenseBasisFactors(source).build(headings, LuPivotPolicy())
        assertTrue(dense is LuBuildResult.Rejected)
        KotlinBasisSolver(source).use { solver ->
            assertTrue(solver.refactorize(headings))
            val rhs = doubleArrayOf(1.0, 1.0)
            val solution = IndexedVector(2).also { it.scatter(rhs) }
            solver.ftran(solution)

            assertEquals(1.0, solution[0])
            assertEquals(0.0, solution[1])
            assertEquals(1L, assertNotNull(solver.basisWork.build).denseAttempts)
            assertTrue(solver.basisOperationWork.refactorization.units > dense.report.units)
            assertTrue(solver.basisOperationWork.complete)
        }
    }

    @Test
    fun `failed dense rebuild invalidates factors and sparse repair returns owned headings`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { solver ->
            assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
            val before = solver.basisOperationWork.units
            assertFalse(solver.refactorize(intArrayOf(0, 0, 0)))
            assertTrue(solver.singular)
            assertNull(solver.ordering())
            assertTrue(solver.basisOperationWork.units > before)
            val repair = assertNotNull(solver.refactorizeRepairing(intArrayOf(0, 0, 0)))

            assertTrue(repair.repaired)
            assertNotNull(solver.ordering())
            assertEquals(0L, assertNotNull(solver.basisWork.build).denseAttempts)
        }
    }
}
