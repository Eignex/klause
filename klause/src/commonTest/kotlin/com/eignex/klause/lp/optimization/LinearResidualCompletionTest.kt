package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.SparseSourceLpLimits
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearResidualCompletionTest {
    @Test
    fun `changed objective costs should discard a completed residual point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(3)),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )

        LinearResidualCompletion(SourceLpBudget(), SparseSourceLpPolicy()).use { completion ->
            val minimum = completion.complete(rows, 1, Cancellation.Never, { _, _ -> },
                listOf(BigFraction.ONE), small = { null })
            val maximum = completion.complete(rows, 1, Cancellation.Never, { _, _ -> },
                listOf(BigFraction.MINUS_ONE), small = { null })

            assertEquals(listOf(BigFraction.ONE), minimum)
            assertEquals(listOf(BigFraction.ofLong(3)), maximum)
        }
    }

    @Test
    fun `a residual lifetime should use cumulative caller funding beyond the baseline floating allowance`() {
        val funding = SourceLpFunding(1_000_000L, 64L * 1024L * 1024L)
        val budget = SourceLpBudget(funding = funding)
        val policy = SparseSourceLpPolicy(floating = LpFloatAllowance(1L, 1))
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(3) }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(-3) }, BigFraction.MINUS_ONE),
        )
        funding.grant(1_000_000L)

        val point = LinearResidualCompletion(budget, policy).use {
            it.complete(rows, 2, Cancellation.Never, { _, _ -> }, small = { null })
        }

        assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
        assertTrue(funding.spent > 1L)
        assertFalse(funding.exhausted)
    }

    @Test
    fun `sparse completion should recover an exact rational point outside small input admission`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(3) }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(-3) }, BigFraction.MINUS_ONE),
        )

        val point = LinearResidualCompletion(SourceLpBudget(), SparseSourceLpPolicy()).use {
            it.complete(rows, 129, Cancellation.Never, { _, _ -> }, small = { null })
        }

        assertEquals(129, assertNotNull(point).size)
        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `a sparse closed endpoint should not satisfy a strict residual`() {
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE),
            BigFraction.ZERO, strict = true))

        val point = LinearResidualCompletion(SourceLpBudget(), SparseSourceLpPolicy()).use {
            it.complete(rows, 1, Cancellation.Never, { _, _ -> }, small = { null })
        }

        assertNull(point)
    }

    @Test
    fun `unchanged hard declines should reuse their result across fresh row objects`() {
        val budget = SourceLpBudget()
        val policy = SparseSourceLpPolicy(preparation = SparseSourceLpLimits(0, 0))
        var attempts = 0
        var reused = 0L
        val observe: (String, Long) -> Unit = { stage, count ->
            if (stage == "PrimalRecoveryDeclineReused") reused += count
        }

        LinearResidualCompletion(budget, policy).use {
            repeat(2) { _ ->
                val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE))
                assertNull(it.complete(rows, 1, Cancellation.Never, observe, small = { attempts++; null }))
            }
        }

        assertEquals(1, attempts)
        assertEquals(1, budget.operations)
        assertEquals(1L, reused)
    }

    @Test
    fun `changed residual endpoints should discard a retained decline`() {
        var attempts = 0
        val policy = SparseSourceLpPolicy(preparation = SparseSourceLpLimits(0, 0))

        LinearResidualCompletion(SourceLpBudget(), policy).use { completion ->
            for (rhs in listOf(BigFraction.ZERO, BigFraction.ONE)) {
                val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), rhs))
                assertNull(completion.complete(rows, 1, Cancellation.Never, { _, _ -> },
                    small = { attempts++; null }))
            }
        }

        assertEquals(2, attempts)
    }

    @Test
    fun `a funding pause should retain sparse admission and cumulative spending`() {
        val funding = SourceLpFunding(1_000_000, 64L * 1024L * 1024L)
        val budget = SourceLpBudget(funding = funding)
        val rows = listOf(ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE))
        funding.grant(1024)

        LinearResidualCompletion(budget, SparseSourceLpPolicy()).use { completion ->
            assertNull(completion.complete(rows, 1, Cancellation.Never, { _, _ -> }, small = { null }))
            assertTrue(completion.paused)
            val spent = funding.spent
            funding.grant(100_000)
            val point = completion.complete(rows, 1, Cancellation.Never, { _, _ -> },
                small = { error("retained residual") })

            assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
            assertFalse(completion.paused)
            assertTrue(funding.spent > spent)
            assertEquals(1, budget.operations)
        }
    }
}
