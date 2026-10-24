package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HyperSparseSolveTest {
    @Test
    fun `scatter should retain unique touches through cancellation and repeated indices`() {
        val work = BasisWorkspace(3)
        work.set(1, 2.0)
        val slice = BasisSlice(intArrayOf(9, 1, 0, 1, 2, 9), doubleArrayOf(9.0, -2.0, 3.0, 4.0, 0.0, 9.0), 1, 4)

        work.scatter(1.0, slice, length = 2)
        work.scatter(1.0, slice, start = 2, length = 2)

        assertEquals(3, work.count)
        assertContentEquals(intArrayOf(1, 0, 2), work.indices)
        assertContentEquals(doubleArrayOf(3.0, 4.0, 0.0), work.values)
        work.clear()
        work.scatter(2.0, slice)
        assertEquals(3, work.count)
        assertContentEquals(doubleArrayOf(6.0, 4.0, 0.0), work.values)
    }

    @Test
    fun `scatter arithmetic failure should leave modified slots clearable`() {
        for ((alpha, coefficient) in listOf(1e200 to 1e200, 1e-200 to 1e-200, 1.0 to Double.NaN)) {
            val work = BasisWorkspace(2)
            val slice = BasisSlice(intArrayOf(0, 1), doubleArrayOf(1.0, coefficient))

            assertFailsWith<BasisArithmeticException> { work.scatter(alpha, slice) }

            work.clear()
            work.scatter(1.0, BasisSlice(intArrayOf(1), doubleArrayOf(3.0)))
            assertEquals(1, work.count)
            assertContentEquals(doubleArrayOf(0.0, 3.0), work.values)
        }
    }

    @Test
    fun `scatter should reject addition overflow and remain reusable`() {
        val work = BasisWorkspace(1)
        work.set(0, Double.MAX_VALUE)

        assertFailsWith<BasisArithmeticException> {
            work.scatter(1.0, BasisSlice(intArrayOf(0), doubleArrayOf(Double.MAX_VALUE)))
        }

        work.clear()
        assertEquals(0, work.count)
        assertContentEquals(doubleArrayOf(0.0), work.values)
    }

    @Test
    fun `scatter should check live slice indices on every traversal`() {
        val work = BasisWorkspace(2)
        val indices = intArrayOf(0, 1)
        val slice = BasisSlice(indices, doubleArrayOf(2.0, 3.0))
        work.scatter(1.0, slice)
        work.clear()
        indices[1] = 2

        assertFailsWith<IndexOutOfBoundsException> { work.scatter(1.0, slice) }

        work.clear()
        assertEquals(0, work.count)
        assertContentEquals(doubleArrayOf(0.0, 0.0), work.values)
    }

    @Test
    fun `scatter should reject invalid windows before changing the workspace`() {
        val work = BasisWorkspace(2)
        work.set(0, 7.0)
        val slice = BasisSlice(intArrayOf(0), doubleArrayOf(3.0))

        for ((start, length) in listOf(-1 to 1, 0 to -1, 1 to 1, Int.MAX_VALUE to 1)) {
            assertFailsWith<IllegalArgumentException> { work.scatter(1.0, slice, start, length) }
        }

        assertEquals(1, work.count)
        assertContentEquals(doubleArrayOf(7.0, 0.0), work.values)
    }

    @Test
    fun `scatter should reject workspace input aliases before mutation`() {
        val work = BasisWorkspace(2)
        work.set(0, 7.0)

        for (slice in listOf(
            BasisSlice(work.indices, doubleArrayOf(3.0, 4.0)),
            BasisSlice(intArrayOf(0, 1), work.values),
        )) {
            assertFailsWith<IllegalArgumentException> { work.scatter(1.0, slice) }
        }

        assertEquals(1, work.count)
        assertContentEquals(doubleArrayOf(7.0, 0.0), work.values)
    }

    @Test
    fun `hypersparse solve should visit only dependencies and switch when support grows`() {
        for (lower in listOf(false, true)) {
            val n = 40
            val matrix = SparseMatrix.ofColumns(
                n,
                n,
                List(n) { j ->
                    buildList {
                        add(j to 2.0)
                        if (lower && j < 2) add(j + 1 to -1.0)
                        if (!lower && j > n - 3) add(j - 1 to -1.0)
                    }
                },
            )
            for (threshold in listOf(0.05, 0.2)) {
                val solver = HyperSparseSolve(matrix, lower, unitDiagonal = false, threshold)
                val work = BasisWorkspace(n)
                val root = if (lower) 0 else n - 1
                work.set(root, 8.0)

                val report = solver.solve(work, 0.0)

                assertEquals(threshold == 0.2, report.sparse)
                assertEquals(if (report.sparse) 3 else n, report.pivotVisits)
                for (i in 0 until n) {
                    var product = 0.0
                    for (j in 0 until n) product += matrix[i, j] * work.values[j]
                    assertEquals(if (i == root) 8.0 else 0.0, product)
                }
            }
        }
    }

    @Test
    fun `exact cancellation should remove output support and clear the workspace`() {
        val matrix = SparseMatrix.ofColumns(4, 4, listOf(listOf(1 to 2.0), emptyList(), emptyList(), emptyList()))
        val solver = HyperSparseSolve(matrix, lower = true, unitDiagonal = true, densityThreshold = 1.0)
        val work = BasisWorkspace(4)
        val vector = IndexedVector(4)
        vector.store(0, 1.0)
        vector.store(1, 2.0)
        vector.store(3, -0.0)
        work.load(vector)

        assertTrue(solver.solve(work, 0.0).sparse)
        work.write(vector)

        assertEquals(1, vector.count)
        assertContentEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0), vector.toDoubleArray())
        assertContentEquals(DoubleArray(4), work.values)
        assertEquals(0, work.count)
    }

    @Test
    fun `failed output mapping should clear the workspace for reuse`() {
        val work = BasisWorkspace(2)
        val vector = IndexedVector(2)
        work.set(0, 3.0)
        work.set(1, 4.0)

        assertFailsWith<IllegalArgumentException> { work.write(vector, intArrayOf(0, 0)) }

        assertContentEquals(DoubleArray(2), work.values)
        assertEquals(0, work.count)
        work.set(1, 5.0)
        work.write(vector)
        assertContentEquals(doubleArrayOf(0.0, 5.0), vector.toDoubleArray())
    }

    @Test
    fun `checked scatter failure should preserve caller vectors and recover on the next solve`() {
        for ((coefficient, rhs) in listOf(1e200 to 1e200, 1e-200 to 1e-200)) {
            val matrix = SparseMatrix.ofColumns(
                2,
                2,
                listOf(listOf(0 to 1.0), listOf(0 to coefficient, 1 to 1.0)),
            )
            val solver = KotlinBasisSolver(matrix)
            assertTrue(solver.refactorize(intArrayOf(0, 1)))
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(2).also { it.store(if (transpose) 0 else 1, rhs) }
                val before = vector.toDoubleArray()

                assertFailsWith<ArithmeticException> {
                    if (transpose) solver.btran(vector, 0.0) else solver.ftran(vector, 0.0)
                }

                assertContentEquals(before, vector.toDoubleArray())
                assertEquals(1, vector.count)
                vector.unit(if (transpose) 1 else 0)
                if (transpose) solver.btran(vector, 0.0) else solver.ftran(vector, 0.0)
                assertEquals(1.0, vector[if (transpose) 1 else 0])
                assertEquals(1, vector.count)
            }
            solver.close()
        }
    }
}
