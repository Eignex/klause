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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class F64BasisFactorsTest {
    @Test
    fun `factors reconstruct each directed shape in original coordinates`() {
        val shapes = listOf("empty", "unit", "triangular", "sparse", "spiked", "dense", "dense16")
        for (shape in shapes) {
            val n = when (shape) {
                "empty" -> 0
                "dense16" -> 16
                else -> 8
            }
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
            val random = Random(23)
            val rowOrder = (0 until n).shuffled(random)
            val columnOrder = (0 until n).shuffled(random)
            val permuted = Array(n) { i -> DoubleArray(n) { j -> original[rowOrder[i]][columnOrder[j]] } }
            val basis = IntArray(n) { n - 1 - it }
            val matrix = sparse(permuted)

            val result = assertIs<LuBuildResult.Built>(F64BasisFactors(matrix).build(basis), shape)
            val residual = reconstructionResidual(matrix, basis, result.factors)

            assertTrue(residual <= 1e-12, "$shape residual $residual")
            assertEquals(n, result.work.pivots, shape)
            if (shape == "unit" || shape == "triangular") {
                assertEquals(n, result.work.singletonPivots, shape)
                assertEquals(0, result.work.schurUpdates, shape)
                assertEquals(0, result.work.kernelDimension, shape)
            }
            println("B2a $shape residual=$residual ${result.work}")
        }
    }

    @Test
    fun `row singletons and a permuted dense kernel reconstruct together`() {
        val matrix = sparse(
            arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(2.0, 3.0, 4.0),
                doubleArrayOf(0.0, 5.0, 6.0),
            ),
        )
        val basis = intArrayOf(2, 0, 1)

        val result = assertIs<LuBuildResult.Built>(F64BasisFactors(matrix).build(basis))

        assertEquals(0, result.factors.symbolic.rowOrder[0])
        assertEquals(1, result.factors.symbolic.columnOrder[0])
        assertTrue(reconstructionResidual(matrix, basis, result.factors) <= 1e-12)
    }

    @Test
    fun `numerically small pivots decline without asserting exact singularity`() {
        for ((delta, accepted) in listOf(1e-8 to true, 1e-12 to false, 0.0 to false)) {
            val matrix = sparse(arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0 + delta)))
            val basis = intArrayOf(0, 1)

            val result = F64BasisFactors(matrix).build(basis)

            if (accepted) {
                val built = assertIs<LuBuildResult.Built>(result)
                assertTrue(reconstructionResidual(matrix, basis, built.factors) <= 1e-12)
            } else {
                assertEquals(LuBuildRejection.NO_USABLE_PIVOT, assertIs<LuBuildResult.Rejected>(result).reason)
                assertEquals(1, result.work.pivots)
            }
        }
    }

    @Test
    fun `tighter absolute tolerance permits a nonsingular tiny pivot`() {
        val matrix = sparse(arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0 + 1e-12)))
        val basis = intArrayOf(0, 1)

        val result = F64BasisFactors(matrix).build(basis, LuPivotPolicy(absoluteTolerance = 1e-14))

        val built = assertIs<LuBuildResult.Built>(result)
        assertTrue(reconstructionResidual(matrix, basis, built.factors) <= 1e-12)
    }

    @Test
    fun `relative threshold skips an unstable row singleton`() {
        val matrix = sparse(arrayOf(doubleArrayOf(1e-8, 0.0), doubleArrayOf(1.0, 1.0)))
        val basis = intArrayOf(0, 1)

        val built = assertIs<LuBuildResult.Built>(F64BasisFactors(matrix).build(basis))

        assertEquals(1, built.factors.symbolic.rowOrder[0])
        assertTrue(reconstructionResidual(matrix, basis, built.factors) <= 1e-12)
    }

    @Test
    fun `unusable singleton columns do not hide usable kernel pivots`() {
        val matrix = sparse(
            arrayOf(
                doubleArrayOf(1e-12, 0.0, 0.0),
                doubleArrayOf(0.0, 2.0, 1.0),
                doubleArrayOf(0.0, 1.0, 2.0),
            ),
        )

        val result = F64BasisFactors(matrix).build(intArrayOf(0, 1, 2))

        val rejected = assertIs<LuBuildResult.Rejected>(result)
        assertEquals(LuBuildRejection.NO_USABLE_PIVOT, rejected.reason)
        assertEquals(2, rejected.work.pivots)
    }

    @Test
    fun `rank deficient selections decline and a later valid build recovers`() {
        val matrix = sparse(arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0)))
        val builder = F64BasisFactors(matrix)
        val valid = intArrayOf(1, 0)
        val first = assertIs<LuBuildResult.Built>(builder.build(valid))
        for (basis in listOf(intArrayOf(0, 0), intArrayOf(0, 2))) {
            val failure = assertIs<LuBuildResult.Rejected>(builder.build(basis))
            assertEquals(LuBuildRejection.NO_USABLE_PIVOT, failure.reason)
            assertEquals(failure.work, builder.build(basis).work)
        }

        val recovered = assertIs<LuBuildResult.Built>(builder.build(valid))

        assertEquals(first.work, recovered.work)
        assertEquals(first.factors.lower, recovered.factors.lower)
        assertEquals(first.factors.upper, recovered.factors.upper)
        assertTrue(reconstructionResidual(matrix, valid, recovered.factors) <= 1e-12)
    }

    @Test
    fun `nonfinite selected inputs decline and finite selections remain usable`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val matrix = sparse(arrayOf(doubleArrayOf(value, 1.0)))
            val builder = F64BasisFactors(matrix)

            val failure = assertIs<LuBuildResult.Rejected>(builder.build(intArrayOf(0)))
            val success = assertIs<LuBuildResult.Built>(builder.build(intArrayOf(1)))

            assertEquals(LuBuildRejection.NONFINITE_INPUT, failure.reason)
            assertTrue(reconstructionResidual(matrix, intArrayOf(1), success.factors) <= 1e-12)
        }
    }

    @Test
    fun `overflow and nonzero underflow decline during construction`() {
        val matrices = listOf(
            arrayOf(doubleArrayOf(1e308, 1e308), doubleArrayOf(1e308, -1e308)),
            arrayOf(doubleArrayOf(1e200, 1e-200), doubleArrayOf(1e-200, 2e-200)),
            arrayOf(doubleArrayOf(1.0, 1e-200), doubleArrayOf(1e-200, 1e-200)),
        )
        for (matrix in matrices) {
            val builder = F64BasisFactors(sparse(matrix))

            val result = builder.build(intArrayOf(0, 1), LuPivotPolicy(absoluteTolerance = 0.0))

            assertEquals(LuBuildRejection.ARITHMETIC_BREAKDOWN, assertIs<LuBuildResult.Rejected>(result).reason)
            assertEquals(result.work, builder.build(intArrayOf(0, 1), LuPivotPolicy(absoluteTolerance = 0.0)).work)
        }
    }

    @Test
    fun `repeated kernel builds preserve factors permutations and work`() {
        val matrix = sparse(
            Array(8) { i ->
                DoubleArray(8) { j -> if (i == j) 10.0 else ((i * 3 + j * 5) % 7 - 3).toDouble() }
            },
        )
        val builder = F64BasisFactors(matrix)
        val basis = IntArray(8) { 7 - it }
        val first = assertIs<LuBuildResult.Built>(builder.build(basis))

        val second = assertIs<LuBuildResult.Built>(builder.build(basis))

        assertEquals(first.work, second.work)
        assertContentEquals(first.factors.symbolic.rowOrder, second.factors.symbolic.rowOrder)
        assertContentEquals(first.factors.symbolic.columnOrder, second.factors.symbolic.columnOrder)
        assertEquals(first.factors.lower, second.factors.lower)
        assertEquals(first.factors.upper, second.factors.upper)
        assertTrue(reconstructionResidual(matrix, basis, second.factors) <= 1e-12)
    }

    @Test
    fun `a deficient proposed pivot falls back to ordinary ordering`() {
        val matrix = sparse(
            arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0, 1.0),
                doubleArrayOf(0.0, 1.0, 1.0, 1.0),
            ),
        )
        val builder = F64BasisFactors(matrix)
        val old = assertIs<LuBuildResult.Built>(builder.build(intArrayOf(0, 1)))
        val changedBasis = intArrayOf(2, 3)

        val rebuilt = assertIs<LuBuildResult.Built>(
            builder.build(changedBasis, IntArray(2) { -1 }, proposedOrder = old.factors.symbolic),
        )

        assertTrue(rebuilt.report.proposedOrder)
        assertFalse(rebuilt.report.reusedOrder)
        assertTrue(rebuilt.report.fallback)
        assertEquals(LuBuildRejection.NO_USABLE_PIVOT, rebuilt.report.proposedRejection)
        assertTrue(assertNotNull(rebuilt.report.proposedWork).pivots < changedBasis.size)
        assertTrue(rebuilt.report.units > rebuilt.work.units)
        assertTrue(reconstructionResidual(matrix, changedBasis, rebuilt.factors) <= 1e-12)
    }

    @Test
    fun `fresh ordering alone decides failure after a rejected proposal`() {
        val matrix = sparse(
            arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0),
                doubleArrayOf(0.0, 1.0, 0.0),
            ),
        )
        val builder = F64BasisFactors(matrix)
        val old = assertIs<LuBuildResult.Built>(builder.build(intArrayOf(0, 1)))

        val rejected = assertIs<LuBuildResult.Rejected>(
            builder.build(intArrayOf(0, 2), IntArray(2) { -1 }, proposedOrder = old.factors.symbolic),
        )

        assertEquals(LuBuildRejection.NO_USABLE_PIVOT, rejected.reason)
        assertTrue(rejected.report.fallback)
        assertEquals(LuBuildRejection.NO_USABLE_PIVOT, rejected.report.proposedRejection)
        assertTrue(rejected.report.units > rejected.work.units)
    }

    @Test
    fun `malformed proposed orders fall back to ordinary ordering`() {
        val builder = F64BasisFactors(sparse(arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0))))
        val duplicate = SymbolicLu(intArrayOf(0, 1), intArrayOf(0, 1)).also { it.rowOrder[1] = 0 }
        val outOfRange = SymbolicLu(intArrayOf(0, 1), intArrayOf(0, 1)).also { it.columnOrder[1] = 2 }
        val proposals = listOf(
            SymbolicLu(intArrayOf(0), intArrayOf(0)),
            SymbolicLu(intArrayOf(0, 1, 2), intArrayOf(0, 1, 2)),
            duplicate,
            outOfRange,
        )

        for (proposal in proposals) {
            val result = assertIs<LuBuildResult.Built>(
                builder.build(intArrayOf(0, 1), IntArray(2) { -1 }, proposedOrder = proposal),
            )

            assertTrue(result.report.proposedOrder)
            assertFalse(result.report.reusedOrder)
            assertTrue(result.report.fallback)
            assertEquals(LuBuildRejection.NO_USABLE_PIVOT, result.report.proposedRejection)
            assertNotNull(result.report.proposedWork)
            assertTrue(reconstructionResidual(builderSource(), intArrayOf(0, 1), result.factors) <= 1e-12)
        }
    }

    @Test
    fun `input and returned cache mutation cannot change later builds`() {
        val pointers = intArrayOf(0, 2, 4)
        val indices = intArrayOf(0, 1, 0, 1)
        val values = doubleArrayOf(2.0, 1.0, 1.0, 3.0)
        val matrix = SparseMatrix.wrap(2, 2, pointers, indices, values)
        val builder = F64BasisFactors(matrix)
        val basis = intArrayOf(1, 0)
        val first = assertIs<LuBuildResult.Built>(builder.build(basis))
        val expectedUpper = first.factors.upper.values.copyOf()
        val expectedLower = first.factors.lower.values.copyOf()
        pointers.fill(0)
        indices.fill(0)
        values.fill(0.0)
        basis.fill(0)
        first.factors.basisColumns.fill(0)
        first.factors.symbolic.rowOrder.fill(0)
        first.factors.upper.values.fill(-1.0)
        first.factors.lower.values.fill(-1.0)

        val repeated = assertIs<LuBuildResult.Built>(builder.build(intArrayOf(1, 0)))

        assertContentEquals(expectedUpper, repeated.factors.upper.values)
        assertContentEquals(expectedLower, repeated.factors.lower.values)
        assertContentEquals(intArrayOf(1, 0), repeated.factors.basisColumns)
        assertEquals(first.work, repeated.work)
    }

    @Test
    fun `explicit zeros do not create pivots or factor entries`() {
        val matrix = SparseMatrix.wrap(
            2,
            2,
            intArrayOf(0, 2, 4),
            intArrayOf(0, 1, 0, 1),
            doubleArrayOf(0.0, 1.0, 1.0, -0.0),
        )

        val result = assertIs<LuBuildResult.Built>(F64BasisFactors(matrix).build(intArrayOf(0, 1)))

        assertEquals(2, result.work.factorEntries)
        assertTrue(reconstructionResidual(matrix, intArrayOf(0, 1), result.factors) <= 1e-12)
    }

    @Test
    fun `invalid basis dimensions columns and pivot policies are rejected`() {
        val builder = F64BasisFactors(sparse(arrayOf(doubleArrayOf(1.0))))
        for (basis in listOf(intArrayOf(), intArrayOf(-1), intArrayOf(1))) {
            assertFailsWith<IllegalArgumentException> { builder.build(basis) }
        }
        for (tolerance in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { LuPivotPolicy(absoluteTolerance = tolerance) }
        }
        for (threshold in listOf(0.0, 1.1, Double.NaN)) {
            assertFailsWith<IllegalArgumentException> { LuPivotPolicy(relativeThreshold = threshold) }
        }
        assertFailsWith<IllegalArgumentException> { LuPivotPolicy(searchLimit = 0) }
    }

    private fun sparse(values: Array<DoubleArray>): SparseMatrix {
        val cols = values.firstOrNull()?.size ?: 0
        return SparseMatrix.ofColumns(
            values.size,
            cols,
            List(cols) { j ->
                values.indices.filter { values[it][j] != 0.0 }.map { it to values[it][j] }
            },
        )
    }

    private fun builderSource(): SparseMatrix = sparse(arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 1.0)))

    private fun reconstructionResidual(source: SparseMatrix, basis: IntArray, factors: LuFactors): Double {
        val n = basis.size
        val l = factors.lower.toArray()
        val u = factors.upper.toArray()
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i <= j) assertEquals(0.0, l[i][j])
                if (i > j) assertEquals(0.0, u[i][j])
                assertEquals(l[i][j], factors.lowerTranspose[j, i])
                assertEquals(u[i][j], factors.upperTranspose[j, i])
            }
            l[i][i] = 1.0
        }
        val rebuilt = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                var value = 0.0
                for (k in 0 until n) value += l[i][k] * u[k][j]
                rebuilt[factors.symbolic.rowOrder[i]][factors.symbolic.columnOrder[j]] = value
            }
        }
        var error = 0.0
        var scale = 1.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                val expected = source[i, basis[j]]
                error = max(error, abs(rebuilt[i][j] - expected))
                scale = max(scale, abs(expected))
            }
        }
        return error / scale
    }
}
