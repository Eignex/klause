package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PolynomialAffineReductionTest {
    @Test
    fun `containing cancellation withholds a partially eliminated region`() {
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val polynomial = arithmetic.subtract(Polynomial.variable(0), Polynomial.variable(1))
        val formula = PolynomialFormula.conjunction(listOf(
            PolynomialAtom(polynomial, PolynomialRelation.GE),
            PolynomialAtom(polynomial, PolynomialRelation.LE),
        ), arithmetic)
        val region = PolynomialBooleanRegion(mapOf(0 to true, 1 to true), emptyMap())
        val pending = PolynomialAffineReduction.prepare(formula, region, 2, arithmetic)
        assertNull(pending.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
