package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForrestTomlinFactorsTest {
    @Test
    fun `updates and rebuilds agree with original source and fresh factors`() {
        for (shape in listOf("sparse", "spiked", "dense", "near")) {
            val source = ftSource(shape)
            val ft = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 100.0)
            val eta = BasisEtaReference(source)
            val fresh = KotlinBasisSolver(source)
            val basis = IntArray(source.rows) { source.rows - 1 - it }
            assertTrue(ft.refactorize(basis))
            assertTrue(eta.refactorize(basis))
            val random = Random(193)
            var residual = 0.0
            var rebuilds = 0
            for (step in 0..24) {
                assertTrue(fresh.refactorize(basis))
                repeat(if (step == 12) 2 else 1) { rebuilt ->
                    if (rebuilt == 1) {
                        assertTrue(ft.refactorize(basis))
                        assertTrue(eta.refactorize(basis))
                        assertEquals(0, ft.updateCount)
                        rebuilds++
                    }
                    for (transpose in listOf(false, true)) {
                        for (hint in listOf(0.0, 1.0)) {
                            val rhs = DoubleArray(source.rows) { if (it == step % source.rows) 1.0 else 0.0 }
                            val expected = IndexedVector(source.rows).also { it.scatter(rhs) }
                            if (transpose) fresh.btran(expected, hint) else fresh.ftran(expected, hint)
                            for (solver in listOf(ft, eta)) {
                                val vector = IndexedVector(source.rows).also { it.scatter(rhs) }
                                if (transpose) solver.btran(vector, hint) else solver.ftran(vector, hint)
                                val error = ftResidual(source, basis, rhs, vector, transpose)
                                residual = max(residual, error)
                                assertTrue(error <= if (shape == "near") 1e-8 else 1e-11, "$shape $step $error")
                                for (i in basis.indices) {
                                    assertTrue(abs(vector[i] - expected[i]) <= 1e-8 * max(1.0, abs(expected[i])))
                                }
                                assertEquals(vector.toDoubleArray().count { it != 0.0 }, vector.count)
                            }
                        }
                    }
                }
                if (step == 24) break
                val slot = if (step % 3 == 0) 2 else random.nextInt(source.rows)
                val entering = (basis[slot] + source.rows) % source.cols
                for (solver in listOf(ft, eta)) {
                    val spike = IndexedVector(source.rows).also { it.scatterColumn(source, entering) }
                    solver.ftran(spike, 0.0)
                    assertEquals(BasisUpdate.APPLIED, solver.update(slot, entering, spike, spike))
                    spike.clear()
                }
                basis[slot] = entering
            }
            println("B3 fresh $shape residual=$residual accepted=24 rejected=0 rebuilds=$rebuilds")
            ft.close()
            eta.close()
            fresh.close()
        }
    }

    @Test
    fun `updated row and column adjacency describe the same permuted triangular matrix`() {
        val source = ftSource("dense")
        val basis = IntArray(source.rows) { source.rows - 1 - it }
        val initial = assertIs<LuBuildResult.Built>(BasisFactors(source).build(basis)).factors
        val ft = ForrestTomlinFactors(initial)
        val solver = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 100.0)
        assertTrue(solver.refactorize(basis))
        for (step in 0 until 16) {
            val slot = (step * 3) % source.rows
            val entering = (basis[slot] + source.rows) % source.cols
            val spike = IndexedVector(source.rows).also { it.scatterColumn(source, entering) }
            solver.ftran(spike)
            val mapped = BasisWorkspace(source.rows)
            mapped.load(spike, initial.symbolic.columnPosition)
            val pivot = initial.symbolic.columnPosition[slot]

            assertTrue(ft.update(pivot, mapped, 1e-10))

            assertEquals(pivot, ft.upper.order.last())
            assertContentEquals(ft.upper.order, ft.transpose.order)
            for (i in basis.indices) {
                for (j in basis.indices) {
                    assertEquals(ft.upper.columns[j][i], ft.transpose.columns[i][j])
                    if (i > j) assertEquals(0.0, ft.upper.columns[ft.upper.order[j]][ft.upper.order[i]])
                }
                assertTrue(ft.upper.columns[i][i] != 0.0)
            }
            assertEquals(BasisUpdate.APPLIED, solver.update(slot, entering, spike))
            basis[slot] = entering
        }
    }

    @Test
    fun `fill advice adopts a usable basis before the update count limit`() {
        val source = SparseMatrix.ofColumns(
            3,
            4,
            listOf(listOf(0 to 1.0), listOf(1 to 1.0), listOf(2 to 1.0), List(3) { it to 1.0 }),
        )
        val solver = KotlinBasisSolver(source, updateLimit = 100, fillFactor = 1.0)
        assertTrue(solver.refactorize(intArrayOf(0, 1, 2)))
        val spike = IndexedVector(3).also { it.scatterColumn(source, 3) }
        solver.ftran(spike)

        assertEquals(BasisUpdate.REFACTORIZE, solver.update(0, 3, spike))

        assertEquals(1, solver.updateCount)
        assertTrue(solver.nnz > 3)
        val report = assertNotNull(solver.lastUpdateWork)
        assertEquals(solver.nnz, report.upperEntries + report.transformEntries)
        assertEquals(null, solver.refactorizeReason)
        for (transpose in listOf(false, true)) {
            val rhs = doubleArrayOf(2.0, 3.0, 4.0)
            spike.scatter(rhs)
            if (transpose) solver.btran(spike) else solver.ftran(spike)
            assertEquals(0.0, ftResidual(source, intArrayOf(3, 1, 2), rhs, spike, transpose))
        }
    }

    @Test
    fun `checked update arithmetic declines atomically and scratch recovers`() {
        // Each spike has a representable pivot and ratios; U times spike fails in scratch.
        for ((diagonal, incoming) in listOf(1e200 to 1e200, 1e-200 to 1e-200)) {
            val matrix = SparseMatrix.ofColumns(2, 2, listOf(listOf(0 to diagonal), listOf(1 to 1.0)))
            val initial = assertIs<LuBuildResult.Built>(
                BasisFactors(matrix).build(intArrayOf(0, 1), LuPivotPolicy(absoluteTolerance = 0.0)),
            ).factors
            val ft = ForrestTomlinFactors(initial)
            val before = ft.upper.columns
            val beforeTranspose = ft.transpose.columns
            val beforeOrder = ft.upper.order.copyOf()
            val spike = BasisWorkspace(2)
            spike.set(initial.symbolic.columnPosition[0], incoming)

            assertFalse(ft.update(initial.symbolic.columnPosition[0], spike, 0.0))

            assertTrue(before === ft.upper.columns)
            assertTrue(beforeTranspose === ft.transpose.columns)
            assertContentEquals(beforeOrder, ft.upper.order)
            assertEquals(0, ft.updateCount)
            spike.clear()
            spike.set(initial.symbolic.columnPosition[0], 1.0)
            assertTrue(ft.update(initial.symbolic.columnPosition[0], spike, 0.0))
            assertEquals(1, ft.updateCount)
        }
    }

    @Test
    fun `displaced row arithmetic declines without publishing adjacency or transforms`() {
        val matrix = SparseMatrix.ofColumns(
            2,
            2,
            listOf(listOf(0 to 1.0), listOf(0 to 1e200, 1 to 1e-200)),
        )
        val initial = assertIs<LuBuildResult.Built>(
            BasisFactors(matrix).build(intArrayOf(0, 1), LuPivotPolicy(absoluteTolerance = 0.0)),
        ).factors
        val ft = ForrestTomlinFactors(initial)
        val before = ft.upper.columns
        val rows = ft.transpose.columns
        val spike = BasisWorkspace(2)
        spike.set(0, 1.0)
        spike.set(1, 1.0)

        assertFalse(ft.update(initial.symbolic.columnPosition[0], spike, 0.0))

        assertTrue(before === ft.upper.columns)
        assertTrue(rows === ft.transpose.columns)
        assertEquals(0, ft.updateCount)
        assertEquals(0, ft.transformEntries)
        assertTrue(assertNotNull(ft.lastUpdateWork).units > 0)
    }

    @Test
    fun `copy work includes both staged diagonal adjacency views`() {
        val matrix = SparseMatrix.ofColumns(1, 1, listOf(listOf(0 to 1.0)))
        val initial = assertIs<LuBuildResult.Built>(BasisFactors(matrix).build(intArrayOf(0))).factors
        for ((value, expected) in listOf(1.0 to 2L, 2.0 to 3L)) {
            val ft = ForrestTomlinFactors(initial)
            val spike = BasisWorkspace(1).also { it.set(0, value) }

            assertTrue(ft.update(0, spike, 1e-10))

            assertEquals(expected, assertNotNull(ft.lastUpdateWork).copiedEntries)
        }
    }
}

internal fun ftSource(shape: String, n: Int = 8): SparseMatrix {
    val random = Random(23)
    val rows = (0 until n).shuffled(random)
    val slots = (0 until n).shuffled(random)
    return SparseMatrix.ofColumns(
        n,
        2 * n,
        List(2 * n) { column ->
            List(n) { row ->
                val i = rows[row]
                val j = slots[column % n]
                val base = when {
                    i == j -> if (shape == "near" && i == n - 1) 1e-7 else (n + 2).toDouble()
                    shape == "near" && i < j -> 1.0
                    shape == "sparse" && (i == (j + 1) % n || j == (i + 1) % n) -> -1.0
                    shape == "spiked" && (i == 2 || j == 5) -> 1.0
                    shape == "dense" -> ((i * 3 + j * 5) % 7 - 3).toDouble()
                    else -> 0.0
                }
                val extra = when {
                    column < n -> 0.0
                    shape == "near" -> 0.125 * base
                    i == j -> 0.5
                    i == (j + 3) % n -> 0.125
                    else -> 0.0
                }
                row to (base + extra)
            }
        },
    )
}

internal fun ftResidual(
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

// Test-only product-form comparator. Base factors are never updated; no production engine uses this path.
internal class BasisEtaReference(private val source: SparseMatrix) : BasisSolver {
    private val base = KotlinBasisSolver(source)
    private val work = BasisWorkspace(source.rows)
    private val rows = mutableListOf<Int>()
    private val spikes = mutableListOf<DoubleArray>()
    private var headings = IntArray(0)
    var solveEntries = 0L
        private set
    override val n: Int get() = base.n
    override val nnz: Int get() = base.nnz + spikes.sumOf { it.count { value -> value != 0.0 } }
    override val updateCount: Int get() = rows.size
    override val singular: Boolean get() = base.singular
    override val rcond: Double get() = base.rcond

    override fun refactorize(basicIndex: IntArray): Boolean {
        rows.clear()
        spikes.clear()
        headings = basicIndex.copyOf()
        return base.refactorize(basicIndex)
    }

    override fun ftran(x: IndexedVector, expectedDensity: Double) {
        base.ftran(x, expectedDensity)
        solveEntries = base.lastSolveWork!!.let { it.first.arithmeticEntries + it.second.arithmeticEntries }
        work.load(x)
        for (k in rows.indices) {
            val row = rows[k]
            val spike = spikes[k]
            if (work.values[row] == 0.0) continue
            val value = work.values[row] / spike[row]
            for (i in spike.indices) {
                if (i != row && spike[i] != 0.0) work.set(i, work.values[i] - spike[i] * value)
            }
            work.set(row, value)
            solveEntries += spike.count { it != 0.0 } - 1
        }
        work.write(x)
    }

    override fun btran(x: IndexedVector, expectedDensity: Double) {
        var entries = 0L
        work.load(x)
        for (k in rows.size - 1 downTo 0) {
            val row = rows[k]
            val spike = spikes[k]
            var value = work.values[row]
            for (i in spike.indices) {
                if (i != row && spike[i] != 0.0) value -= spike[i] * work.values[i]
            }
            work.set(row, value / spike[row])
            entries += spike.count { it != 0.0 } - 1
        }
        work.write(x)
        base.btran(x, expectedDensity)
        solveEntries = entries + base.lastSolveWork!!.let { it.first.arithmeticEntries + it.second.arithmeticEntries }
    }

    override fun update(pivotRow: Int, entering: Int, spike: IndexedVector, pivotEta: IndexedVector?): BasisUpdate {
        rows.add(pivotRow)
        spikes.add(spike.toDoubleArray())
        headings[pivotRow] = entering
        return BasisUpdate.APPLIED
    }

    override fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean): BasisSolveQuality {
        val residual = ftResidual(source, headings, rhs, solution, transpose)
        var absolute = 0.0
        for (i in headings.indices) {
            var product = 0.0
            for (j in headings.indices) {
                product += (if (transpose) source[j, headings[i]] else source[i, headings[j]]) * solution[j]
            }
            absolute = max(absolute, abs(product - rhs[i]))
        }
        return BasisSolveQuality(absolute, residual)
    }

    override fun close() {
        base.close()
        rows.clear()
        spikes.clear()
    }
}
