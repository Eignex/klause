package com.eignex.klause.cli

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.unaryMinus
import kotlin.test.Test
import kotlin.test.assertEquals

class SmtAlgebraicValueTest {
    @Test
    fun `rational defining coefficients render the same exact selected real root`() {
        val coefficients = listOf(
            BigFraction.of(bigIntOf(-2), bigIntOf(3)),
            BigFraction.ZERO,
            BigFraction.of(bigIntOf(1), bigIntOf(3)),
        )

        val value = smtPolynomialRoot(coefficients, rootIndex = 2)

        assertEquals("(root-obj (+ (- 2) (* 1 (^ x 2))) 2)", value)
    }

    @Test
    fun `root coefficients preserve integers beyond machine precision`() {
        val coefficient = parseBigInt("123456789012345678901234567890")

        val value = smtPolynomialRoot(
            listOf(BigFraction.of(-coefficient, BIG_ONE), BigFraction.ZERO, BigFraction.ONE),
            rootIndex = 2,
        )

        assertEquals("(root-obj (+ (- 123456789012345678901234567890) (* 1 (^ x 2))) 2)", value)
    }

    @Test
    fun `source symbol rendering preserves spaces and numeric identities`() {
        for ((source, expected) in listOf("source x" to "|source x|", "2" to "|2|")) {
            assertEquals(expected, smtSourceSymbol(source))
        }
    }
}
