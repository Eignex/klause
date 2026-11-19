package com.eignex.klause.meta.coreguided

import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreGuidedContinuationTest {
    @Test
    fun `completed near attainment progress should fund bounded caller time`() {
        val budget = CoreGuidedContinuation(2_000L)

        val funded = budget.fund(1L, bigIntOf(1L), bigIntOf(1L), 2.0, 500L, 40_000L)

        assertTrue(funded)
        assertEquals(30_500L, budget.maximumMillis)
        assertEquals(1L, budget.grants)
    }

    @Test
    fun `the same completed core should not renew its funding`() {
        val budget = CoreGuidedContinuation(2_000L)
        budget.fund(1L, bigIntOf(1L), bigIntOf(1L), 2.0, 500L, 40_000L)

        val funded = budget.fund(1L, bigIntOf(1L), bigIntOf(1L), 2.0, 10_000L, 40_000L)

        assertFalse(funded)
        assertEquals(30_500L, budget.maximumMillis)
        assertEquals(1L, budget.grants)
    }

    @Test
    fun `fresh completed progress should extend an existing grant`() {
        val budget = CoreGuidedContinuation(2_000L)
        budget.fund(1L, bigIntOf(1L), bigIntOf(1L), 2.0, 500L, 40_000L)

        val funded = budget.fund(2L, bigIntOf(2L), bigIntOf(1L), 3.0, 20_000L, 20_000L)

        assertTrue(funded)
        assertEquals(35_000L, budget.maximumMillis)
        assertEquals(2L, budget.grants)
    }

    @Test
    fun `insufficient progress or caller resources should keep the initial limit`() {
        val lower = bigIntOf(1L)
        val rise = bigIntOf(1L)
        for ((cores, incumbent, remaining) in listOf<Triple<Long, Double, Long?>>(
            Triple(0L, 2.0, 40_000L),
            Triple(1L, 3.0, 40_000L),
            Triple(1L, Double.POSITIVE_INFINITY, 40_000L),
            Triple(1L, Double.NaN, 40_000L),
            Triple(1L, 2.0, null),
            Triple(1L, 2.0, 7_999L),
            Triple(1L, 1.0, 40_000L),
        )) {
            val budget = CoreGuidedContinuation(2_000L)

            assertFalse(budget.fund(cores, lower, rise, incumbent, 0L, remaining))
            assertEquals(2_000L, budget.maximumMillis)
            assertEquals(0L, budget.grants)
        }
    }
}
