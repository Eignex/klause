package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.SearchAtomPremise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NormalizedSmtTermTest {
    @Test
    fun `rational multiples reconstruct the original expression exactly`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val source = mapOf(7 to half, 2 to BigFraction.ofLong(-3), 4 to BigFraction.ZERO)
        val point = mapOf(2 to BigFraction.ofLong(5), 7 to BigFraction.ofLong(-4))
        val reference = normalizeSmtTerm(source)
        for (scale in listOf(half, BigFraction.ofLong(-7), BigFraction.ONE)) {
            val expression = source.mapValues { (_, coefficient) -> coefficient * scale }
            val normalized = normalizeSmtTerm(expression)
            val activity = normalized.coefficients.entries.fold(BigFraction.ZERO) { sum, (column, coefficient) ->
                sum + coefficient * point.getValue(column)
            }
            val expected = expression.entries.fold(BigFraction.ZERO) { sum, (column, coefficient) ->
                sum + coefficient * (point[column] ?: BigFraction.ZERO)
            }

            assertEquals(reference.coefficients, normalized.coefficients)
            assertEquals(expected, normalized.sourceValue(activity))
        }
    }

    @Test
    fun `fixed offsets reconstruct source activity and transformed thresholds`() {
        val expression = mapOf(0 to BigFraction.ofLong(-2), 1 to BigFraction.ofLong(3))
        val fixed = mapOf(1 to FixedSmtColumn(BigFraction.ofLong(4), SearchAtomPremise.All(emptyList())))

        val term = normalizeSmtTerm(expression, fixed)

        assertEquals(BigFraction.ofLong(2), term.bound(BigFraction.ofLong(8)))
        assertEquals(BigFraction.ofLong(8), term.sourceValue(BigFraction.ofLong(2)))
        assertEquals(listOf(SmtTermSubstitution(1, BigFraction.ofLong(3), fixed.getValue(1))), term.substitutions)
    }

    @Test
    fun `all fixed and zero expressions retain their exact constant`() {
        val fixed = mapOf(0 to FixedSmtColumn(BigFraction.ofLong(3), SearchAtomPremise.All(emptyList())))
        for (coefficient in listOf(BigFraction.ZERO, BigFraction.ofLong(-2))) {
            val term = normalizeSmtTerm(mapOf(0 to coefficient), fixed)

            assertEquals(emptyMap(), term.coefficients)
            assertEquals(coefficient * BigFraction.ofLong(3), term.sourceValue(BigFraction.ZERO))
        }
    }

    @Test
    fun `source identities and unequal exact ratios do not share`() {
        val half = BigFraction.ofLong(2).reciprocal()
        val reference = normalizeSmtTerm(mapOf(0 to BigFraction.ONE, 1 to half))
        val nearby = BigFraction.ofLong(1L shl 54).reciprocal() + half

        assertNotEquals(reference.coefficients, normalizeSmtTerm(mapOf(0 to BigFraction.ONE, 1 to nearby)).coefficients)
        assertNotEquals(reference.coefficients, normalizeSmtTerm(mapOf(0 to BigFraction.ONE, 2 to half)).coefficients)
    }
}
