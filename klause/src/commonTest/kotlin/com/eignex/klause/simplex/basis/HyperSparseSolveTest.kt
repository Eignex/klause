package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HyperSparseSolveTest {
    @Test
    fun `DFS reaches only dependencies and switches when support grows`() {
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
                val first = work.values.copyOf()
                work.clear()
                work.set(root, 8.0)
                assertEquals(report, solver.solve(work, 0.0))
                assertContentEquals(first, work.values)
            }
        }
    }

    @Test
    fun `exact cancellation removes output support and workspace clears every touched entry`() {
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
        work.clear()
        assertContentEquals(DoubleArray(4), work.values)
        assertEquals(0, work.count)
    }

    @Test
    fun `dense hint skips graph traversal even for a sparse right hand side`() {
        val matrix = SparseMatrix.ofColumns(10, 10, List(10) { listOf(it to 1.0) })
        val work = BasisWorkspace(10)
        work.set(7, 3.0)

        val report = HyperSparseSolve(matrix, lower = false, unitDiagonal = false).solve(work, 1.0)

        assertFalse(report.sparse)
        assertEquals(0, report.reachEntries)
        assertEquals(10, report.pivotVisits)
        assertEquals(1, work.count)
    }

    @Test
    fun `checked scatter failures preserve caller vectors and recover on the next solve`() {
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

    @Test
    fun `declined solves report completed traversal and pivot work`() {
        val matrix = SparseMatrix.ofColumns(
            2,
            2,
            listOf(listOf(0 to 1.0), listOf(0 to 1e200, 1 to 1.0)),
        )
        val solver = HyperSparseSolve(matrix, lower = false, unitDiagonal = false)
        val work = BasisWorkspace(2).also { it.set(1, 1e200) }

        assertFailsWith<BasisArithmeticException> { solver.solve(work, 1.0) }

        val declined = assertNotNull(solver.lastWork)
        assertEquals(1, declined.pivotVisits)
        assertTrue(declined.units > 0)
    }
}
