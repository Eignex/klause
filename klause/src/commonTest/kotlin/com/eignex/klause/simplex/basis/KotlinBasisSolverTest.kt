package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinBasisSolverTest {
    @Test
    fun `empty solves should retain zero support without visiting updated factors`() {
        val source = ftSource("dense", 3)
        KotlinBasisSolver(source).use { solver ->
            assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
            val spike = IndexedVector(3).also { it.scatterColumn(source, 4) }
            solver.ftran(spike)
            assertEquals(BasisUpdate.APPLIED, solver.update(1, 4, spike))
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(3)
                val before = if (transpose) solver.basisWork.btran else solver.basisWork.ftran

                if (transpose) solver.btran(vector, 1.0) else solver.ftran(vector, 1.0)

                assertContentEquals(DoubleArray(3), vector.toDoubleArray())
                val after = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertEquals(before.attempts + 1, after.attempts)
                assertEquals(before.successes + 1, after.successes)
                assertEquals(before.units, after.units)
                assertEquals(0L, assertNotNull(solver.lastSolveWork).units)
            }
        }
    }

    @Test
    fun `empty solves should require usable open factors`() {
        val source = ftSource("dense", 3)
        val solver = KotlinBasisSolver(source)
        val vector = IndexedVector(3)
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
        assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
        assertFalse(solver.refactorize(intArrayOf(0, 0, 0)))
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
        solver.close()
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
    }

    @Test
    fun `declined solve work should be retained until the next build`() {
        val source = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1e-200)))
        val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
        assertTrue(solver.refactorize(intArrayOf(0)))
        val vector = IndexedVector(1).also { it.store(0, 1e200) }
        assertFailsWith<BasisArithmeticException> { solver.ftran(vector) }
        val declined = solver.basisWork
        assertEquals(1, declined.ftran.declines)
        assertTrue(declined.ftran.units > 0)
        assertFalse(solver.basisOperationWork.complete)
        assertTrue(solver.basisOperationWork.ftran.units > 0)
        vector.scatter(doubleArrayOf(1e-200))
        solver.ftran(vector)

        assertTrue(solver.refactorize(intArrayOf(0)))
        assertEquals(BasisPhaseWork(), solver.basisWork.ftran)
        assertTrue(solver.basisOperationWork.ftran.units >= declined.ftran.units)
        solver.close()
    }

    @Test
    fun `seeded updates should solve permuted sparse bases in both directions`() {
        assertSeededUpdatesSolve("sparse", 8)
    }

    @Test
    fun `singular update should preserve the prior basis`() {
        val source = SparseMatrix.ofColumns(
            2,
            5,
            listOf(
                listOf(0 to 1.0),
                listOf(1 to 1.0),
                listOf(0 to 1.0, 1 to 1.0),
                listOf(0 to 1e-12),
                listOf(0 to Double.NaN),
            ),
        )
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
        solver.ftran(spike)
        assertEquals(BasisUpdate.APPLIED, solver.update(0, 2, spike))
        for (entering in listOf(1, 3, 4)) {
            spike.scatterColumn(source, entering)
            if (entering != 4) solver.ftran(spike)
            val before = solver.nnz

            assertEquals(BasisUpdate.SINGULAR, solver.update(0, entering, spike))

            assertEquals(1, solver.updateCount)
            assertEquals(before, solver.nnz)
            assertNull(solver.refactorizeReason)
            val rhs = doubleArrayOf(3.0, 4.0)
            for (transpose in listOf(false, true)) {
                val solved = IndexedVector(2).also { it.scatter(rhs) }
                if (transpose) solver.btran(solved) else solver.ftran(solved)
                assertEquals(0.0, sourceResidual(source, intArrayOf(2, 1), rhs, solved, transpose))
            }
        }
    }

    @Test
    fun `failed refactorization should invalidate factors until a successful rebuild`() {
        val source = SparseMatrix.ofColumns(2, 3, listOf(listOf(0 to 1.0), listOf(1 to 1.0), listOf(0 to 2.0)))
        val solver = KotlinBasisSolver(source)
        assertTrue(solver.refactorize(intArrayOf(0, 1)))
        val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
        solver.ftran(spike)
        assertEquals(BasisUpdate.APPLIED, solver.update(0, 2, spike))
        assertFalse(solver.refactorize(intArrayOf(2, 2)))
        assertTrue(solver.singular)
        assertEquals(0, solver.nnz)
        assertEquals(0.0, solver.rcond)
        assertFailsWith<IllegalStateException> { solver.ftran(spike) }
        assertFailsWith<IllegalStateException> { solver.btran(spike) }
        assertEquals(BasisUpdate.SINGULAR, solver.update(0, 0, spike))
        assertTrue(solver.refactorize(intArrayOf(1, 0)))
        assertEquals(0, solver.updateCount)
    }

    @Test
    fun `arithmetic failure should leave the argument intact and recover scratch`() {
        for ((pivot, rhs) in listOf(1e-200 to 1e200, 1e200 to 1e-200)) {
            val matrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to pivot)))
            val solver = KotlinBasisSolver(matrix, LuPivotPolicy(absoluteTolerance = 0.0))
            assertTrue(solver.refactorize(intArrayOf(0)))
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(1).also { it.store(0, rhs) }
                val before = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertFailsWith<BasisArithmeticException> {
                    if (transpose) solver.btran(vector) else solver.ftran(vector)
                }
                assertEquals(rhs, vector[0])
                val declined = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertEquals(before.attempts + 1, declined.attempts)
                assertEquals(before.successes, declined.successes)
                assertTrue(declined.units > before.units)
                vector.scatter(doubleArrayOf(pivot))
                if (transpose) solver.btran(vector) else solver.ftran(vector)
                assertEquals(1.0, vector[0])
                val recovered = if (transpose) solver.basisWork.btran else solver.basisWork.ftran
                assertEquals(before.attempts + 2, recovered.attempts)
                assertEquals(before.successes + 1, recovered.successes)
            }
        }
    }

    @Test
    fun `tiny update pivot should solve against the entering source column`() {
        val source = SparseMatrix.ofColumns(1, 2, listOf(listOf(0 to 1.0), listOf(0 to 1e-200)))
        val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
        assertTrue(solver.refactorize(intArrayOf(0)))
        val spike = IndexedVector(1).also { it.scatterColumn(source, 1) }
        solver.ftran(spike)

        assertEquals(BasisUpdate.APPLIED, solver.update(0, 1, spike))

        for (transpose in listOf(false, true)) {
            val vector = IndexedVector(1).also { it.store(0, 1e-200) }
            if (transpose) solver.btran(vector) else solver.ftran(vector)
            assertEquals(1.0, vector[0])
            assertEquals(0.0, sourceResidual(source, intArrayOf(1), doubleArrayOf(1e-200), vector, transpose))
        }
    }

    @Test
    fun `unrepresentable spike normalization should decline without changing the basis`() {
        for ((pivot, offDiagonal) in listOf(1e-200 to 1e200, 1e200 to 1e-200, 1e-320 to 1.0)) {
            val source = SparseMatrix.ofColumns(
                2,
                3,
                listOf(
                    listOf(0 to 1.0),
                    listOf(1 to 1.0),
                    listOf(0 to pivot, 1 to offDiagonal),
                ),
            )
            val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
            assertTrue(solver.refactorize(intArrayOf(0, 1)))
            val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
            solver.ftran(spike)

            assertEquals(BasisUpdate.SINGULAR, solver.update(0, 2, spike))

            assertEquals(0, solver.updateCount)
            val rhs = doubleArrayOf(2.0, 3.0)
            spike.scatter(rhs)
            solver.ftran(spike)
            assertContentEquals(rhs, spike.toDoubleArray())
        }
    }

    @Test
    fun `ordering snapshot should own its inputs and returned arrays`() {
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
    fun `ordering offer should decline after accepted updates and survive rejected ones`() {
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
    fun `repair should export logical columns and failed rebuild should retire the order`() {
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

    private fun assertSeededUpdatesSolve(shape: String, n: Int) {
        val original = Array(n) { i ->
            DoubleArray(n) { j ->
                when {
                    shape == "unit" -> if (i == j) 1.0 else 0.0
                    i == j -> (n + 2).toDouble()
                    shape == "triangular" && i < j -> ((i + j) % 3 - 1).toDouble()
                    shape == "sparse" && (j == (i + 1) % n || i == (j + 1) % n) -> -1.0
                    shape == "spiked" && (i == 2 || j == 5) -> 1.0
                    shape == "dense" -> ((i * 3 + j * 5) % 7 - 3).toDouble()
                    else -> 0.0
                }
            }
        }
        val permutation = Random(23)
        val rows = (0 until n).shuffled(permutation)
        val slots = (0 until n).shuffled(permutation)
        val source = SparseMatrix.ofColumns(
            n,
            2 * n,
            List(2 * n) { j ->
                List(n) { i ->
                    val slot = j % n
                    val extra = if (j >= n && rows[i] == slots[slot]) 0.5 else 0.0
                    i to (original[rows[i]][slots[slot]] + extra)
                }
            },
        )
        var previousReports: List<BasisSolveWork>? = null
        repeat(3) {
            val solver = KotlinBasisSolver(source, updateLimit = 4, fillFactor = 100.0)
            val basis = IntArray(n) { n - 1 - it }
            assertTrue(solver.refactorize(basis))
            val reports = mutableListOf<BasisSolveWork>()
            val random = Random(71)
            for (step in 0..12) {
                for (dense in listOf(false, true)) {
                    val rhs = DoubleArray(n) {
                        when {
                            dense -> (it % 3 - 1).toDouble()
                            it == step % n -> 1.0
                            else -> 0.0
                        }
                    }
                    for (hint in listOf(0.0, 1.0)) {
                        for (transpose in listOf(false, true)) {
                            val vector = IndexedVector(n).also { it.scatter(rhs) }
                            if (transpose) solver.btran(vector, hint) else solver.ftran(vector, hint)
                            val residual = sourceResidual(source, basis, rhs, vector, transpose)
                            assertTrue(
                                residual <= 1e-11,
                                "$shape step=$step transpose=$transpose residual=$residual",
                            )
                            assertTrue(solver.solveQuality(rhs, vector, transpose).relativeResidual <= 1e-11)
                            assertEquals(vector.toDoubleArray().count { it != 0.0 }, vector.count)
                            reports.add(assertNotNull(solver.lastSolveWork))
                        }
                    }
                }
                if (step == 12) break
                val row = random.nextInt(n)
                val entering = (basis[row] + n) % (2 * n)
                val spike = IndexedVector(n).also { it.scatterColumn(source, entering) }
                solver.ftran(spike, 0.0)
                val outcome = solver.update(row, entering, spike, spike)
                assertEquals(if (step < 3) BasisUpdate.APPLIED else BasisUpdate.REFACTORIZE, outcome)
                basis[row] = entering
                spike.clear()
            }
            previousReports?.let { assertEquals(it, reports) }
            previousReports = reports
            solver.close()
        }
    }

    private fun sourceResidual(
        source: SparseMatrix,
        basis: IntArray,
        rhs: DoubleArray,
        solution: IndexedVector,
        transpose: Boolean,
    ): Double {
        var error = 0.0
        var scale = 1.0
        for (i in basis.indices) {
            var product = 0.0
            for (j in basis.indices) {
                product += (if (transpose) source[j, basis[i]] else source[i, basis[j]]) * solution[j]
            }
            error = max(error, abs(product - rhs[i]))
            scale = max(scale, max(abs(product), abs(rhs[i])))
        }
        return error / scale
    }
}
