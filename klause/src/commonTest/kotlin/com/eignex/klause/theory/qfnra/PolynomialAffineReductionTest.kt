package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialAffineReductionTest {
    @Test
    fun `affine bound consequences remain visible without coordinate elimination`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val cube = arithmetic.power(x, 3)
        val atoms = listOf(PolynomialAtom(y, PolynomialRelation.GE),
            PolynomialAtom(arithmetic.subtract(cube, y), PolynomialRelation.GT),
            PolynomialAtom(arithmetic.subtract(arithmetic.power(y, 2), Polynomial.ONE), PolynomialRelation.GE))
        val formula = PolynomialFormula.conjunction(atoms, arithmetic)
        val region = PolynomialBooleanRegion(formula.atoms.indices.associateWith { true }, emptyMap())

        val reduction = PolynomialAffineReduction.prepare(formula, region, 2, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(reduction.inconsistent)
        assertTrue(reduction.changed)
        assertEquals(0, reduction.eliminatedCount)
        assertEquals(PolynomialAtom(cube, PolynomialRelation.GT), reduction.atoms.first())
        assertTrue(atoms.all { it in reduction.atoms })
    }

    @Test
    fun `predicate remainders retain changes without eliminating coordinates`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val norm = arithmetic.add(arithmetic.power(x, 2), arithmetic.power(y, 2))
        val expression = arithmetic.add(norm, x)
        val equation = PolynomialAtom(arithmetic.subtract(norm, Polynomial.ONE), PolynomialRelation.EQ)
        val formula = PolynomialFormula.conjunction(listOf(
            equation,
            PolynomialAtom(arithmetic.subtract(arithmetic.scale(expression, BigFraction.ofLong(4)),
                Polynomial.constant(BigFraction.ofLong(5))), PolynomialRelation.GT),
            PolynomialAtom(arithmetic.subtract(arithmetic.scale(expression, BigFraction.ofLong(3)),
                Polynomial.constant(BigFraction.ofLong(4))), PolynomialRelation.LT),
        ), arithmetic)
        val region = PolynomialBooleanRegion(mapOf(0 to true, 1 to true, 2 to true), emptyMap())

        val result = PolynomialAffineReduction.prepare(formula, region, 2, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(result.inconsistent)
        assertTrue(result.changed)
        assertEquals(0, result.eliminatedCount)
        assertEquals(listOf(equation,
            PolynomialAtom(arithmetic.subtract(arithmetic.scale(x, BigFraction.ofLong(4)), Polynomial.ONE),
                PolynomialRelation.GT),
            PolynomialAtom(arithmetic.subtract(arithmetic.scale(x, BigFraction.ofLong(3)), Polynomial.ONE),
                PolynomialRelation.LT)), result.atoms)
    }

    @Test
    fun `coincident weak graph bounds eliminate a coordinate with nonlinear occurrences`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val graph = arithmetic.subtract(y, arithmetic.scale(x, BigFraction.ofLong(8)))
        val formula = PolynomialFormula.conjunction(listOf(
            PolynomialAtom(graph, PolynomialRelation.LE),
            PolynomialAtom(arithmetic.scale(graph, BigFraction.ofLong(-2)), PolynomialRelation.LE),
            PolynomialAtom(arithmetic.multiply(x, y), PolynomialRelation.GT),
        ), arithmetic)
        val region = PolynomialBooleanRegion(mapOf(0 to true, 1 to true, 2 to true), emptyMap())

        val result = PolynomialAffineReduction.prepare(formula, region, 2, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(result.inconsistent)
        assertEquals(1, result.eliminatedCount)
        val atom = result.atoms.single()
        assertEquals(PolynomialRelation.GT, atom.relation)
        assertEquals(arithmetic.scale(arithmetic.multiply(x, x), BigFraction.ofLong(8)), atom.polynomial)
    }

    @Test
    fun `graph substitution exposes further coincident weak bounds`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val z = Polynomial.variable(2)
        val formula = PolynomialFormula.conjunction(listOf(
            PolynomialAtom(arithmetic.subtract(z, y), PolynomialRelation.EQ),
            PolynomialAtom(arithmetic.subtract(x, z), PolynomialRelation.LE),
            PolynomialAtom(arithmetic.subtract(y, x), PolynomialRelation.LE),
            PolynomialAtom(arithmetic.multiply(x, y), PolynomialRelation.GT),
        ), arithmetic)
        val region = PolynomialBooleanRegion(mapOf(0 to true, 1 to true, 2 to true, 3 to true), emptyMap())

        val result = PolynomialAffineReduction.prepare(formula, region, 3, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(result.inconsistent)
        assertEquals(2, result.eliminatedCount)
        assertEquals(PolynomialAtom(arithmetic.multiply(x, x), PolynomialRelation.GT), result.atoms.single())
    }

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
