package com.eignex.klause.lp

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactMixedEchelonHermiteTest {

    @Test
    fun `preserves double-bounded activities through mixed recovery`() {
        val source = listOf(
            row(mapOf(0 to 1, 1 to 2, 2 to -2), lower = 1, upper = 1),
            row(mapOf(0 to 1), lower = 0, upper = 0),
        )

        val reduced = checkNotNull(exactMixedEchelonHermite(source, realColumns = 1, integerColumns = 2))
        val transformed = listOf(BigFraction.ofLong(3), BigFraction.ofLong(-2), BigFraction.ofLong(4))
        val recovered = reduced.recover(transformed)

        for (index in source.indices) {
            assertEquals(activity(source[index], recovered), activity(reduced.rows[index], transformed))
        }
    }

    @Test
    fun `scales rational integer tails together with their activity range`() {
        val source = listOf(
            row(mapOf(0 to 2, 1 to 1), lower = 1, upper = 3),
            row(mapOf(0 to 1), lower = 1, upper = 2),
        )

        val reduced = checkNotNull(exactMixedEchelonHermite(source, realColumns = 1, integerColumns = 1))
        val transformed = listOf(BigFraction.ofLong(2), BigFraction.ofLong(5))
        val recovered = reduced.recover(transformed)
        val factor = reduced.rows[1].lower * source[1].lower.reciprocal()

        assertEquals(source[1].lower * factor, reduced.rows[1].lower)
        assertEquals(source[1].upper * factor, reduced.rows[1].upper)
        assertEquals(activity(source[1], recovered) * factor, activity(reduced.rows[1], transformed))
    }

    @Test
    fun `forward substitution detects an affine integer contradiction behind a real pivot`() {
        val source = listOf(
            row(mapOf(0 to 1, 1 to 2, 2 to -2), lower = 1, upper = 1),
            row(mapOf(0 to 1), lower = 0, upper = 0),
        )

        val reduced = checkNotNull(exactMixedEchelonHermite(source, realColumns = 1, integerColumns = 2))
        val bounds = exactMixedTriangularBounds(reduced)

        assertTrue(bounds.inconsistent)
        assertEquals(BigInteger.ONE, bounds.realLower[0]?.num)
    }

    private fun row(coefficients: Map<Int, Long>, lower: Long, upper: Long): ExactMixedBoundedRow =
        ExactMixedBoundedRow(
            coefficients.mapValues { BigFraction.ofLong(it.value) },
            BigFraction.ofLong(lower),
            BigFraction.ofLong(upper),
        )

    private fun activity(row: ExactMixedBoundedRow, values: List<BigFraction>): BigFraction =
        row.coefficients.entries.fold(
            BigFraction.ZERO,
        ) { sum, (column, coefficient) -> sum + coefficient * values[column] }
}
