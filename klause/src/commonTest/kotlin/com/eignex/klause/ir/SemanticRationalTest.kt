package com.eignex.klause.ir

import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SemanticRationalTest {
    @Test
    fun `equivalent signed fractions should retain the same normalized value`() {
        val expected = SemanticRational.of(bigIntOf(1), bigIntOf(2))

        val actual = SemanticRational.of(bigIntOf(-2), bigIntOf(-4))

        assertEquals(expected, actual)
        assertEquals(expected.hashCode(), actual.hashCode())
    }

    @Test
    fun `rational ordering should preserve differences below binary64 precision`() {
        val lower = SemanticRational.of(bigIntOf(9_007_199_254_740_992), bigIntOf(10))
        val upper = SemanticRational.of(bigIntOf(9_007_199_254_740_993), bigIntOf(10))

        assertTrue(lower < upper)
    }

    @Test
    fun `a zero denominator should be rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SemanticRational.of(bigIntOf(1), bigIntOf(0))
        }
    }
}
