package com.eignex.klause.simplex.basis

import com.eignex.koblas.SparseMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BasisFactorsTest {
    @Test
    fun `resource limits decline before publishing factors and leave the builder reusable`() {
        val matrix = sparse(arrayOf(doubleArrayOf(3.0, 1.0), doubleArrayOf(1.0, 3.0)))
        val builder = BasisFactors(matrix)
        for ((policy, reason) in listOf(
            LuPivotPolicy(maxEntries = 2L) to LuBuildRejection.STORAGE,
            LuPivotPolicy(maxWork = 1L) to LuBuildRejection.WORK,
            LuPivotPolicy(maxWork = 100L, cancelled = { true }) to LuBuildRejection.CANCELLED,
        )) {
            val declined = builder.build(intArrayOf(0, 1), policy)

            assertEquals(reason, assertIs<LuBuildResult.Rejected>(declined).reason)
            assertIs<LuBuildResult.Built>(builder.build(intArrayOf(0, 1)))
        }
    }

    @Test
    fun `factors should reconstruct each shape in original coordinates`() {
        for (shape in listOf("empty", "unit", "triangular", "spiked", "dense16")) {
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
                        shape == "spiked" && (i == 2 || j == 5) -> 1.0
                        shape == "dense16" -> ((i * 3 + j * 5) % 7 - 3).toDouble()
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

            val result = assertIs<LuBuildResult.Built>(BasisFactors(matrix).build(basis), shape)
            val residual = reconstructionResidual(matrix, basis, result.factors)

            assertTrue(residual <= 1e-12, "$shape residual $residual")
            assertEquals(n, result.work.pivots, shape)
            if (shape == "unit" || shape == "triangular") {
                assertEquals(n, result.work.singletonPivots, shape)
                assertEquals(0, result.work.schurUpdates, shape)
                assertEquals(0, result.work.kernelDimension, shape)
            }
        }
    }

    @Test
    fun `small pivots should decline without asserting exact singularity`() {
        for ((delta, accepted) in listOf(1e-8 to true, 1e-12 to false, 0.0 to false)) {
            val matrix = sparse(arrayOf(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0 + delta)))
            val basis = intArrayOf(0, 1)

            val result = BasisFactors(matrix).build(basis)

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
    fun `relative threshold should skip an unstable row singleton`() {
        val matrix = sparse(arrayOf(doubleArrayOf(1e-8, 0.0), doubleArrayOf(1.0, 1.0)))
        val basis = intArrayOf(0, 1)

        val built = assertIs<LuBuildResult.Built>(BasisFactors(matrix).build(basis))

        assertEquals(1, built.factors.symbolic.rowOrder[0])
        assertTrue(reconstructionResidual(matrix, basis, built.factors) <= 1e-12)
    }

    @Test
    fun `rank deficient selection should decline and a later valid build should recover`() {
        val matrix = sparse(arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0)))
        val builder = BasisFactors(matrix)
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
    fun `nonfinite selected inputs should decline while finite selections stay usable`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val matrix = sparse(arrayOf(doubleArrayOf(value, 1.0)))
            val builder = BasisFactors(matrix)

            val failure = assertIs<LuBuildResult.Rejected>(builder.build(intArrayOf(0)))
            val success = assertIs<LuBuildResult.Built>(builder.build(intArrayOf(1)))

            assertEquals(LuBuildRejection.NONFINITE_INPUT, failure.reason)
            assertTrue(reconstructionResidual(matrix, intArrayOf(1), success.factors) <= 1e-12)
        }
    }

    @Test
    fun `construction should decline on overflow and nonzero underflow`() {
        val matrices = listOf(
            arrayOf(doubleArrayOf(1e308, 1e308), doubleArrayOf(1e308, -1e308)),
            arrayOf(doubleArrayOf(1e200, 1e-200), doubleArrayOf(1e-200, 2e-200)),
            arrayOf(doubleArrayOf(1.0, 1e-200), doubleArrayOf(1e-200, 1e-200)),
        )
        for (matrix in matrices) {
            val builder = BasisFactors(sparse(matrix))

            val result = builder.build(intArrayOf(0, 1), LuPivotPolicy(absoluteTolerance = 0.0))

            assertEquals(LuBuildRejection.ARITHMETIC_BREAKDOWN, assertIs<LuBuildResult.Rejected>(result).reason)
            assertEquals(result.work, builder.build(intArrayOf(0, 1), LuPivotPolicy(absoluteTolerance = 0.0)).work)
        }
    }

    @Test
    fun `deficient proposed pivot should fall back to ordinary ordering`() {
        val matrix = sparse(
            arrayOf(
                doubleArrayOf(1.0, 0.0, 0.0, 1.0),
                doubleArrayOf(0.0, 1.0, 1.0, 1.0),
            ),
        )
        val builder = BasisFactors(matrix)
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
