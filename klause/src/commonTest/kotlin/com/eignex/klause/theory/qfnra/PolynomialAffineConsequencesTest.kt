package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialAffineConsequencesTest {
    @Test
    fun `affine endpoints retain strictness beside nonlinear coordinate predicates`() {
        for (lowerStrict in listOf(false, true)) for (upperStrict in listOf(false, true)) {
            val arithmetic = PolynomialArithmetic()
            val x = Polynomial.variable(0)
            val y = Polynomial.variable(1)
            val cube = arithmetic.power(x, 3)
            val atoms = listOf(
                PolynomialAtom(y, if (lowerStrict) PolynomialRelation.GT else PolynomialRelation.GE),
                PolynomialAtom(arithmetic.subtract(cube, y),
                    if (upperStrict) PolynomialRelation.GT else PolynomialRelation.GE),
                PolynomialAtom(arithmetic.subtract(arithmetic.power(y, 2), Polynomial.ONE), PolynomialRelation.GE),
            )

            val consequences = preparePolynomialAffineConsequences(atoms, arithmetic)
                .asSequence().filterNotNull().single()

            assertEquals(listOf(PolynomialAtom(cube,
                if (lowerStrict || upperStrict) PolynomialRelation.GT else PolynomialRelation.GE)), consequences)
        }
    }

    @Test
    fun `variable dependent coefficients supply no affine endpoint`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val atoms = listOf(PolynomialAtom(y, PolynomialRelation.GE),
            PolynomialAtom(arithmetic.subtract(arithmetic.multiply(x, y), Polynomial.ONE), PolynomialRelation.LE),
            PolynomialAtom(arithmetic.subtract(arithmetic.power(y, 2), Polynomial.ONE), PolynomialRelation.GE))

        val consequences = preparePolynomialAffineConsequences(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertTrue(consequences.isEmpty())
    }

    @Test
    fun `equations and disequalities supply no inequality endpoints`() {
        for (relation in listOf(PolynomialRelation.EQ, PolynomialRelation.NE)) {
            val arithmetic = PolynomialArithmetic()
            val x = Polynomial.variable(0)
            val y = Polynomial.variable(1)
            val atoms = listOf(PolynomialAtom(y, PolynomialRelation.GE),
                PolynomialAtom(arithmetic.subtract(arithmetic.power(x, 3), y), relation),
                PolynomialAtom(arithmetic.power(y, 2), PolynomialRelation.GE))

            val consequences = preparePolynomialAffineConsequences(atoms, arithmetic)
                .asSequence().filterNotNull().single()

            assertTrue(consequences.isEmpty())
        }
    }

    @Test
    fun `endpoint pairs that increase variable support are omitted`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val z = Polynomial.variable(2)
        val u = Polynomial.variable(3)
        val v = Polynomial.variable(4)
        val atoms = listOf(
            PolynomialAtom(arithmetic.subtract(y, arithmetic.add(x, z)), PolynomialRelation.GE),
            PolynomialAtom(arithmetic.subtract(arithmetic.add(u, v), y), PolynomialRelation.GE),
            PolynomialAtom(arithmetic.power(y, 2), PolynomialRelation.GE),
        )

        val consequences = preparePolynomialAffineConsequences(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertTrue(consequences.isEmpty())
    }

    @Test
    fun `shared endpoint consequences are retained once`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val z = Polynomial.variable(2)
        val cube = arithmetic.power(x, 3)
        val atoms = listOf(PolynomialAtom(y, PolynomialRelation.GE),
            PolynomialAtom(arithmetic.subtract(cube, y), PolynomialRelation.GT),
            PolynomialAtom(z, PolynomialRelation.GE),
            PolynomialAtom(arithmetic.subtract(cube, z), PolynomialRelation.GT),
            PolynomialAtom(arithmetic.add(arithmetic.power(y, 2), arithmetic.power(z, 2)), PolynomialRelation.GE))

        val consequences = preparePolynomialAffineConsequences(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(listOf(PolynomialAtom(cube, PolynomialRelation.GT)), consequences)
    }

    @Test
    fun `strict coincident endpoints retain their constant contradiction`() {
        val arithmetic = PolynomialArithmetic()
        val y = Polynomial.variable(0)
        val shifted = arithmetic.subtract(y, Polynomial.ONE)
        val atoms = listOf(PolynomialAtom(shifted, PolynomialRelation.GT),
            PolynomialAtom(shifted, PolynomialRelation.LE),
            PolynomialAtom(arithmetic.power(y, 2), PolynomialRelation.GE))

        val consequences = preparePolynomialAffineConsequences(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(listOf(PolynomialAtom(Polynomial.ZERO, PolynomialRelation.GT)), consequences)
    }

    @Test
    fun `interrupted endpoint capture publishes no partial consequences`() {
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val atoms = listOf(PolynomialAtom(y, PolynomialRelation.GE),
            PolynomialAtom(arithmetic.subtract(arithmetic.power(x, 3), y), PolynomialRelation.GT),
            PolynomialAtom(arithmetic.power(y, 2), PolynomialRelation.GE))
        val pending = preparePolynomialAffineConsequences(atoms, arithmetic)
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
