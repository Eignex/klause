package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import kotlin.test.Test
import kotlin.test.assertNull

class PolynomialLinearRegionTest {
    @Test
    fun `linear decision declines an affine disequality`() {
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        val atoms = listOf(PolynomialAtom(Polynomial.variable(0), PolynomialRelation.NE))

        val result = decidePolynomialLinearRegion(atoms, arithmetic, work)

        assertNull(result)
    }

    @Test
    fun `linear decision retains the exact kernel row limit`() {
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work)
        val atoms = List(129) { PolynomialAtom(Polynomial.variable(0), PolynomialRelation.GE) }

        val result = decidePolynomialLinearRegion(atoms, arithmetic, work)

        assertNull(result)
    }
}
