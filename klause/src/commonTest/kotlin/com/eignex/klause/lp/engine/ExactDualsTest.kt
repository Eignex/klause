package com.eignex.klause.lp.engine

import com.eignex.klause.util.isZero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactDualsTest {
    // Equality rows A·x = 1 over nonnegative columns costing 1 each, with every structural column basic.
    private fun squareBasis(matrix: List<DoubleArray>): Pair<LpModel, Basis> {
        val m = matrix.size
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            List(m) { j -> List(m) { i -> ExactLpEntry(i, ExactLpNumber.ofIeee(matrix[i][j])) } },
            List(m) { ExactLpNumber.of(1L) },
            List(m) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) } +
                List(m) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))) },
            List(m) { ExactLpRow() },
            ExactLpObjective(List(m) { ExactLpNumber.of(1L) } + List(m) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val status = Array(2 * m) { if (it < m) VarStatus.BASIC else VarStatus.AT_LOWER }
        return model to Basis(IntArray(m) { it }, status)
    }

    // fl(1/(i + j + 1)): every entry a full-width double, so the exact duals carry denominators of hundreds of bits.
    private val hilbert = List(6) { i -> DoubleArray(6) { j -> 1.0 / (i + j + 1) } }

    @Test
    fun `exact duals of a basis solve its transposed system exactly`() {
        val cases = listOf(
            listOf(doubleArrayOf(3.0, 1.0), doubleArrayOf(1.0, 2.0)) to doubleArrayOf(0.2, 0.4),
            hilbert to DoubleArray(6),
        )
        for ((matrix, start) in cases) {
            val (model, basis) = squareBasis(matrix)
            val exact = assertNotNull(model.exactState).model

            val outcome = exactBasisDuals(model, basis, start)

            val duals = assertNotNull(outcome.duals, "${outcome.decline}")
            for (j in basis.basicVars) {
                assertTrue(duals.reducedTimesDenominator(exact, j).first.isZero(), "column $j of ${matrix.size}")
            }
        }
    }

    @Test
    fun `exact duals decline once their duals outgrow the caps`() {
        val (model, basis) = squareBasis(hilbert)
        val variants = listOf(
            ExactDualLimits(maxSteps = 3) to ExactDualDecline.STEPS,
            ExactDualLimits(maxBits = 128) to ExactDualDecline.BITS,
            ExactDualLimits(allocationBytes = 4096L) to ExactDualDecline.MEMORY,
        )
        for ((limits, reason) in variants) {
            val outcome = exactBasisDuals(model, basis, DoubleArray(6), limits = limits)

            assertNull(outcome.duals)
            assertEquals(reason, outcome.decline)
            assertTrue(outcome.steps <= limits.maxSteps)
        }
    }

}
