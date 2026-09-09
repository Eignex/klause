package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import com.eignex.koblas.sparse.basis.BasisSnapshot
import com.eignex.koblas.sparse.basis.BasisSolver
import com.eignex.koblas.sparse.basis.BasisUpdate
import com.eignex.koblas.sparse.basis.IndexedVector
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
import kotlin.time.TimeSource

class KotlinBasisSolverTest {
    @Test
    fun `seeded updates solve permuted rectangular source bases in both directions`() {
        for (shape in listOf("unit", "triangular", "sparse", "spiked", "dense", "dense16")) {
            val n = if (shape == "dense16") 16 else 8
            val original = Array(n) { i ->
                DoubleArray(n) { j ->
                    when {
                        shape == "unit" -> if (i == j) 1.0 else 0.0
                        i == j -> (n + 2).toDouble()
                        shape == "triangular" && i < j -> ((i + j) % 3 - 1).toDouble()
                        shape == "sparse" && (j == (i + 1) % n || i == (j + 1) % n) -> -1.0
                        shape == "spiked" && (i == 2 || j == 5) -> 1.0
                        shape.startsWith("dense") -> ((i * 3 + j * 5) % 7 - 3).toDouble()
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
            repeat(3) { repetition ->
                val start = TimeSource.Monotonic.markNow()
                val solver = KotlinBasisSolver(source, updateLimit = 4, fillFactor = 100.0)
                val basis = IntArray(n) { n - 1 - it }
                assertTrue(solver.refactorize(basis))
                val reports = mutableListOf<BasisSolveWork>()
                var maxResidual = 0.0
                var applied = 0
                var advisory = 0
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
                                maxResidual = max(maxResidual, residual)
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
                    if (outcome == BasisUpdate.APPLIED) applied++ else advisory++
                    basis[row] = entering
                    spike.clear()
                }
                if (previousReports != null) assertEquals(previousReports, reports)
                previousReports = reports
                val sparse = reports.count { it.first.sparse }
                val visits = reports.sumOf { it.first.pivotVisits + it.second.pivotVisits }
                val reach = reports.sumOf { it.first.reachEntries + it.second.reachEntries }
                val arithmetic = reports.sumOf {
                    it.first.arithmeticEntries + it.second.arithmeticEntries + it.transformEntries
                }
                println(
                    "B3 $shape repetition=$repetition residual=$maxResidual " +
                        "applied=$applied advisory=$advisory declines=0 sparse=$sparse " +
                        "visits=$visits reach=$reach arithmetic=$arithmetic " +
                        "support=${reports.sumOf { it.outputSupport }} elapsed=${start.elapsedNow()}",
                )
                solver.close()
            }
        }
    }

    @Test
    fun `singular updates preserve the prior basis`() {
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
    fun `failed refactorization invalidates factors until a successful rebuild`() {
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
    fun `solver owns source headings and update copies while solves overwrite only their argument`() {
        val source = SparseMatrix.ofColumns(
            2,
            3,
            listOf(listOf(0 to 1.0), listOf(1 to 1.0), listOf(0 to 2.0, 1 to 3.0)),
        )
        val solver = KotlinBasisSolver(source)
        val headings = intArrayOf(1, 0)
        assertTrue(solver.refactorize(headings))
        val spike = IndexedVector(2).also { it.scatterColumn(source, 2) }
        solver.ftran(spike)
        val saved = spike.toDoubleArray()
        assertEquals(BasisUpdate.APPLIED, solver.update(1, 2, spike))
        assertContentEquals(saved, spike.toDoubleArray())
        source.values.fill(0.0)
        headings.fill(0)
        spike.unit(0)

        solver.ftran(spike)

        assertContentEquals(doubleArrayOf(-1.5, 0.5), spike.toDoubleArray())
        assertEquals(0.0, solver.solveQuality(doubleArrayOf(1.0, 0.0), spike).relativeResidual)
    }

    @Test
    fun `empty basis solves have empty support and zero residual`() {
        val solver: BasisSolver = KotlinBasisSolver(SparseMatrix.ofColumns(0, 0, emptyList()))
        assertTrue(solver.refactorize(intArrayOf()))
        val vector = IndexedVector(0)
        solver.ftran(vector, 0.0)
        solver.btran(vector)
        assertEquals(0, vector.count)
        assertEquals(1.0, solver.rcond)
        assertEquals(0.0, solver.solveQuality(doubleArrayOf(), vector).relativeResidual)
        solver.close()
    }

    @Test
    fun `unsupported optional operations retain seam defaults`() {
        val solver: BasisSolver = KotlinBasisSolver(SparseMatrix.ofColumns(0, 0, emptyList()))
        assertTrue(solver.refactorize(intArrayOf()))
        assertNull(solver.kernel)
        assertNull(solver.snapshot())
        val snapshot = object : BasisSnapshot {
            override fun close() = Unit
        }
        assertFalse(solver.restore(snapshot))
        assertFalse(assertNotNull(solver.refactorizeRepairing(intArrayOf())).repaired)
        solver.close()
    }

    @Test
    fun `close is idempotent and rejects mandatory operations and reports`() {
        val solver = KotlinBasisSolver(SparseMatrix.ofColumns(0, 0, emptyList()))
        assertTrue(solver.refactorize(intArrayOf()))
        val vector = IndexedVector(0)
        solver.close()
        solver.close()
        assertEquals(0, solver.n)
        assertFalse(solver.singular)
        assertFailsWith<IllegalStateException> { solver.nnz }
        assertFailsWith<IllegalStateException> { solver.rcond }
        assertFailsWith<IllegalStateException> { solver.updateCount }
        assertFailsWith<IllegalStateException> { solver.refactorize(intArrayOf()) }
        assertFailsWith<IllegalStateException> { solver.ftran(vector) }
        assertFailsWith<IllegalStateException> { solver.btran(vector) }
        assertFailsWith<IllegalStateException> { solver.update(0, 0, vector) }
        assertFailsWith<IllegalStateException> { solver.solveQuality(doubleArrayOf(), vector) }
    }

    @Test
    fun `arithmetic failure leaves the argument intact and scratch recovers`() {
        for ((pivot, rhs) in listOf(1e-200 to 1e200, 1e200 to 1e-200)) {
            val matrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to pivot)))
            val solver = KotlinBasisSolver(matrix, LuPivotPolicy(absoluteTolerance = 0.0))
            assertTrue(solver.refactorize(intArrayOf(0)))
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(1).also { it.store(0, rhs) }
                assertFailsWith<ArithmeticException> {
                    if (transpose) solver.btran(vector) else solver.ftran(vector)
                }
                assertEquals(rhs, vector[0])
                vector.scatter(doubleArrayOf(pivot))
                if (transpose) solver.btran(vector) else solver.ftran(vector)
                assertEquals(1.0, vector[0])
            }
        }
    }

    @Test
    fun `configured tiny update pivots solve against the entering source column`() {
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
    fun `unrepresentable spike normalization declines without changing the basis`() {
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
    fun `invalid arguments reject without damaging usable factors`() {
        val matrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1.0)))
        val solver = KotlinBasisSolver(matrix)
        val vector = IndexedVector(1).also { it.unit(0) }
        assertTrue(solver.refactorize(intArrayOf(0)))
        for (basis in listOf(intArrayOf(), intArrayOf(-1), intArrayOf(1))) {
            assertFailsWith<IllegalArgumentException> { solver.refactorize(basis) }
        }
        for (density in listOf(-1.0, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { solver.ftran(vector, density) }
            assertFailsWith<IllegalArgumentException> { solver.btran(vector, density) }
        }
        assertFailsWith<IllegalArgumentException> { solver.ftran(IndexedVector(2)) }
        assertFailsWith<IllegalArgumentException> { solver.update(-1, 0, vector) }
        assertFailsWith<IllegalArgumentException> { solver.update(0, 1, vector) }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            vector.scatter(doubleArrayOf(value))
            assertFailsWith<IllegalArgumentException> { solver.ftran(vector) }
        }
        vector.unit(0)
        solver.ftran(vector)
        assertEquals(1.0, vector[0])
    }

    @Test
    fun `unrepresentable update products preserve original headings and both solve directions`() {
        for (magnitude in listOf(1e200, 1e-200)) {
            val source = SparseMatrix.ofColumns(
                2,
                3,
                listOf(
                    listOf(0 to magnitude),
                    listOf(0 to magnitude, 1 to 1.0),
                    listOf(1 to magnitude),
                ),
            )
            val solver = KotlinBasisSolver(source, LuPivotPolicy(absoluteTolerance = 0.0))
            assertTrue(solver.refactorize(intArrayOf(0, 1)))
            // Exact products cancel in the source equation; individual floating products are unrepresentable.
            val spike = IndexedVector(2).also { it.scatter(doubleArrayOf(-magnitude, magnitude)) }
            val before = solver.nnz

            assertEquals(BasisUpdate.SINGULAR, solver.update(0, 2, spike, spike))

            assertEquals(before, solver.nnz)
            assertEquals(0, solver.updateCount)
            assertContentEquals(doubleArrayOf(-magnitude, magnitude), spike.toDoubleArray())
            val rhs = doubleArrayOf(magnitude, 0.0)
            for (transpose in listOf(false, true)) {
                val vector = IndexedVector(2).also { it.scatter(rhs) }
                if (transpose) solver.btran(vector) else solver.ftran(vector)
                assertEquals(0.0, sourceResidual(source, intArrayOf(0, 1), rhs, vector, transpose))
                assertEquals(0.0, solver.solveQuality(rhs, vector, transpose).relativeResidual)
            }
            spike.unit(0)
            assertEquals(BasisUpdate.APPLIED, solver.update(0, 0, spike))
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
