package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lowering.divModPolynomialFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialFormula
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DivModPolynomialTest {
    @Test
    fun `polynomial encoding enforces signed variable division and zero exclusion`() {
        for (convention in DivisionConvention.entries) {
            for (values in listOf(longArrayOf(-7, 3, -2, -1), longArrayOf(-7, 3, -3, 2),
                longArrayOf(-7, -3, 2, -1), longArrayOf(-7, -3, 3, 2), longArrayOf(7, -3, -2, 1),
                longArrayOf(0, 0, 0, 0), longArrayOf(7, 3, 1, 4), longArrayOf(7, 3, 2, 2))) {
                val factor = DivMod(0, 1, 2, 3, convention)
                val formula = divModPolynomialFormula(factor) { PolynomialExpression.Variable(SourceRealId(it)) }
                val arithmetic = PolynomialArithmetic()
                val normalized = PolynomialFormula.normalize(formula, arithmetic,
                    values.indices.associateWith { BigFraction.ofLong(values[it]) })
                val signs = normalized.atoms.map { atom ->
                    assertTrue(atom.polynomial.variables.isEmpty())
                    val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                        .compareTo(BigFraction.ZERO)
                    if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
                }.toIntArray()
                val valid = if (values[1] == 0L) false else {
                    val remainder = if (convention == DivisionConvention.TRUNCATING) values[0] % values[1]
                        else (values[0] % values[1] + kotlin.math.abs(values[1])) % kotlin.math.abs(values[1])
                    values[3] == remainder && values[0] == values[1] * values[2] + remainder
                }

                assertEquals(if (valid) PolynomialFormula.TRUE else PolynomialFormula.FALSE,
                    normalized.truth(intArrayOf(), signs, arithmetic), "$convention ${values.contentToString()}")
            }
        }
    }

    @Test
    fun `noninjective remapping retains every polynomial role`() {
        val mapping = VarRemap(intArrayOf(), intArrayOf(0, 1, 0, 1))
        val factor = DivMod(0, 1, 2, 3).remap(mapping) as DivMod
        val formula = divModPolynomialFormula(factor) { PolynomialExpression.Variable(SourceRealId(it)) }
        for (a in -1L..1L) for (b in -1L..1L) {
            val arithmetic = PolynomialArithmetic()
            val normalized = PolynomialFormula.normalize(formula, arithmetic,
                mapOf(0 to BigFraction.ofLong(a), 1 to BigFraction.ofLong(b)))
            val signs = normalized.atoms.map { atom ->
                val sign = (atom.polynomial.terms.singleOrNull()?.coefficient ?: BigFraction.ZERO)
                    .compareTo(BigFraction.ZERO)
                if (atom.holds(sign)) PolynomialFormula.TRUE else PolynomialFormula.FALSE
            }.toIntArray()
            val valid = b != 0L && a / b == a && a % b == b

            assertEquals(if (valid) PolynomialFormula.TRUE else PolynomialFormula.FALSE,
                normalized.truth(intArrayOf(), signs, arithmetic), "$a $b")
        }
    }
}
