package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealResidualRefutationCacheTest {
    @Test
    fun `a cached numerical core should report current row positions without another LP operation`() {
        val upper = ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO)
        val lower = ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)
        val budget = SourceLpBudget(maxOperations = 1)
        val cache = RealResidualRefutationCache()
        assertTrue(refuteRealResidualComponents(3, listOf(upper, lower), budget, Cancellation.Never, cache = cache))
        val rows = listOf(
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ofLong(7)),
            lower, upper,
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ofLong(3)),
        )
        var support: IntArray? = null

        val refuted = refuteRealResidualComponents(3, rows, budget, Cancellation.Never,
            onSupport = { support = it }, cache = cache)

        assertTrue(refuted)
        assertEquals(setOf(1, 2), assertNotNull(support).toSet())
        assertEquals(1, budget.operations)
        assertFalse(refuteRealResidualComponents(3, rows.filterIndexed { index, _ -> index != 2 },
            budget, Cancellation.Never, cache = cache))
        assertEquals(1, budget.operations)
    }

    @Test
    fun `a cached strict core should not refute a closed or weakened boundary`() {
        val strict = ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO, strict = true)
        val lower = ExactRationalInequality(intArrayOf(0, 1),
            listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.ZERO)
        val cache = RealResidualRefutationCache()
        assertTrue(refuteRealResidualComponents(2, listOf(strict, lower), SourceLpBudget(),
            Cancellation.Never, cache = cache))

        for ((rhs, isStrict) in listOf(BigFraction.ZERO to false, BigFraction.ONE to true)) {
            val upper = ExactRationalInequality(strict.columns, strict.coefficients, rhs, isStrict)

            val refuted = refuteRealResidualComponents(2, listOf(upper, lower), SourceLpBudget(),
                Cancellation.Never, cache = cache)

            assertFalse(refuted)
        }
    }

    @Test
    fun `a stopped cache hit should withhold support and retain the original operation allowance`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val cache = RealResidualRefutationCache()
        val budget = SourceLpBudget(maxOperations = 1)
        assertTrue(refuteRealResidualComponents(2, rows, budget, Cancellation.Never, cache = cache))
        var stopped = false
        var support: IntArray? = null

        val refuted = refuteRealResidualComponents(2, rows, budget, Cancellation { stopped },
            onStage = { if (it == "PURE_REAL_EQUALITY_GENERAL_MEMO_HIT") stopped = true },
            onSupport = { support = it }, cache = cache)

        assertTrue(stopped)
        assertFalse(refuted)
        assertNull(support)
        assertEquals(1, budget.operations)
        assertTrue(refuteRealResidualComponents(2, rows, budget, Cancellation.Never, cache = cache))
        assertEquals(1, budget.operations)
    }

    @Test
    fun `an invalid tail should withhold a matching cached core`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val cache = RealResidualRefutationCache()
        val budget = SourceLpBudget(maxOperations = 1)
        assertTrue(refuteRealResidualComponents(2, rows, budget, Cancellation.Never, cache = cache))

        for (lastColumn in listOf(2, 0)) {
            var support: IntArray? = null
            val tail = ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.ONE), BigFraction.ZERO)
            tail.columns[1] = lastColumn

            val refuted = refuteRealResidualComponents(2, rows + tail, budget, Cancellation.Never,
                onSupport = { support = it }, cache = cache)

            assertFalse(refuted)
            assertNull(support)
            assertEquals(1, budget.operations)
        }
    }
}
