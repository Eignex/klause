package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinearPrimalRefinementTest {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)

    @Test
    fun `objective refinement should preserve a wide exact pin`() {
        val wide = BigFraction.ofLong(9007199254740993L)
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE },
                (wide + BigFraction.ONE).negated()),
        )
        val initial = listOf(BigFraction.ofLong(10), wide)

        val refined = LinearResidualCompletion(SourceLpBudget(), SparseSourceLpPolicy()).use { completion ->
            refineLinearPrimal(listOf(real, integer), initial, rows, emptySet(),
                listOf(BigFraction.ofLong(2), BigFraction.MINUS_ONE), Cancellation.Never,
                complete = { residual, costs, token ->
                    completion.complete(residual, costs.size, token, { _, _ -> }, costs, small = { null })
                }, observe = { _, _ -> })
        }

        assertEquals(listOf(BigFraction.ONE, wide), refined)
    }

    @Test
    fun `inactive rows should not restrict objective refinement`() {
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.MINUS_ONE))

        val refined = refineLinearPrimal(listOf(real), listOf(BigFraction.ONE), rows, setOf(0),
            listOf(BigFraction.ONE), Cancellation.Never, complete = { _, _, _ -> listOf(BigFraction.ZERO) },
            observe = { _, _ -> })

        assertEquals(listOf(BigFraction.ZERO), refined)
    }

    @Test
    fun `refinement should respect a maximization objective in minimization units`() {
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(3)))

        val refined = LinearResidualCompletion(SourceLpBudget(), SparseSourceLpPolicy()).use { completion ->
            refineLinearPrimal(listOf(real), listOf(BigFraction.ONE), rows, emptySet(),
                listOf(BigFraction.ofLong(-7)), Cancellation.Never, complete = { residual, costs, token ->
                    completion.complete(residual, costs.size, token, { _, _ -> }, costs, small = { null })
                }, observe = { _, _ -> })
        }

        assertEquals(listOf(BigFraction.ofLong(3)), refined)
    }

    @Test
    fun `a strict endpoint should leave the accepted point intact`() {
        val initial = listOf(BigFraction.ONE)
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE),
            BigFraction.ZERO, strict = true))

        val refined = refineLinearPrimal(listOf(real), initial, rows, emptySet(), listOf(BigFraction.ONE),
            Cancellation.Never, complete = { _, _, _ -> listOf(BigFraction.ZERO) }, observe = { _, _ -> })

        assertNull(refined)
        assertEquals(listOf(BigFraction.ONE), initial)
    }

    @Test
    fun `an unchanged objective should not replace the accepted point`() {
        val initial = listOf(BigFraction.ONE)

        val refined = refineLinearPrimal(listOf(real), initial, emptyList(), emptySet(), listOf(BigFraction.ONE),
            Cancellation.Never, complete = { _, _, _ -> initial }, observe = { _, _ -> })

        assertNull(refined)
    }

    @Test
    fun `a discrete objective should not request continuous refinement`() {
        val refined = refineLinearPrimal(listOf(real, integer), List(2) { BigFraction.ONE }, emptyList(), emptySet(),
            listOf(BigFraction.ZERO, BigFraction.ONE), Cancellation.Never,
            complete = { _, _, _ -> error("constant residual objective") }, observe = { _, _ -> })

        assertNull(refined)
    }
}
