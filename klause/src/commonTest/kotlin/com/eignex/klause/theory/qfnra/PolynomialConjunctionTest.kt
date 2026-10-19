package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PolynomialConjunctionTest {
    @Test
    fun `opposite rational weak bounds retain their representative as an equality`() {
        val arithmetic = PolynomialArithmetic()
        val polynomial = arithmetic.scale(arithmetic.subtract(Polynomial.variable(0), Polynomial.variable(1)),
            BigFraction.ofLong(3))
        val opposite = arithmetic.scale(polynomial, BigFraction.of(bigIntOf(-2), bigIntOf(7)))
        val atoms = listOf(PolynomialAtom(polynomial, PolynomialRelation.LE),
            PolynomialAtom(opposite, PolynomialRelation.LE))

        val result = preparePolynomialConjunction(atoms, arithmetic).asSequence().filterNotNull().single()

        assertFalse(result.inconsistent)
        assertTrue(result.changed)
        val atom = result.atoms.single()
        assertSame(polynomial, atom.polynomial)
        assertEquals(PolynomialRelation.EQ, atom.relation)
    }

    @Test
    fun `compatible sign restrictions intersect exactly`() {
        for ((left, right, expected) in listOf(
            Triple(PolynomialRelation.LE, PolynomialRelation.NE, PolynomialRelation.LT),
            Triple(PolynomialRelation.GE, PolynomialRelation.NE, PolynomialRelation.GT),
            Triple(PolynomialRelation.LE, PolynomialRelation.EQ, PolynomialRelation.EQ),
            Triple(PolynomialRelation.NE, PolynomialRelation.NE, PolynomialRelation.NE),
        )) {
            for (coefficient in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
                val arithmetic = PolynomialArithmetic()
                val polynomial = arithmetic.scale(Polynomial.variable(0), coefficient)
                val atoms = listOf(PolynomialAtom(polynomial, left), PolynomialAtom(polynomial, right))

                val result = preparePolynomialConjunction(atoms, arithmetic).asSequence().filterNotNull().single()

                assertFalse(result.inconsistent)
                assertSame(polynomial, result.atoms.single().polynomial)
                assertEquals(expected, result.atoms.single().relation)
            }
        }
    }

    @Test
    fun `disjoint sign restrictions refute the selected conjunction`() {
        for ((left, right) in listOf(
            PolynomialRelation.EQ to PolynomialRelation.NE,
            PolynomialRelation.LT to PolynomialRelation.GE,
            PolynomialRelation.GT to PolynomialRelation.LE,
        )) {
            val atoms = listOf(PolynomialAtom(Polynomial.variable(0), left),
                PolynomialAtom(Polynomial.variable(0), right))

            val result = preparePolynomialConjunction(atoms, PolynomialArithmetic())
                .asSequence().filterNotNull().single()

            assertTrue(result.inconsistent)
        }
    }

    @Test
    fun `equal monomial supports do not merge different polynomial ratios`() {
        val arithmetic = PolynomialArithmetic()
        val x = Polynomial.variable(0)
        val y = Polynomial.variable(1)
        val atoms = listOf(PolynomialAtom(arithmetic.add(x, y), PolynomialRelation.LE),
            PolynomialAtom(arithmetic.add(x, arithmetic.scale(y, BigFraction.ofLong(2))), PolynomialRelation.GE))

        val result = preparePolynomialConjunction(atoms, arithmetic).asSequence().filterNotNull().single()

        assertFalse(result.inconsistent)
        assertFalse(result.changed)
        assertEquals(atoms, result.atoms)
    }

    @Test
    fun `constant predicates discharge only exact truths`() {
        for ((polynomial, relation, truth) in listOf(
            Triple(Polynomial.ZERO, PolynomialRelation.EQ, true),
            Triple(Polynomial.ZERO, PolynomialRelation.NE, false),
            Triple(Polynomial.ONE, PolynomialRelation.GT, true),
            Triple(Polynomial.ONE, PolynomialRelation.LE, false),
        )) {
            val atoms = listOf(PolynomialAtom(polynomial, relation))

            val result = preparePolynomialConjunction(atoms, PolynomialArithmetic())
                .asSequence().filterNotNull().single()

            assertEquals(!truth, result.inconsistent)
            assertTrue(result.atoms.isEmpty())
        }
    }

    @Test
    fun `coincident product bounds retain a feasible zero factor fibre`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (<= (* x0 x1) 0) (>= (* x0 x1) 0) (= x1 0) (> x0 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ZERO, witness.realValue(1).rational)
        assertTrue(checkNotNull(witness.realValue(0).rational).signum() > 0)
    }

    @Test
    fun `conjunction preparation observes cancellation after suspension`() {
        var stopped = false
        val atoms = listOf(PolynomialAtom(Polynomial.variable(0), PolynomialRelation.LE),
            PolynomialAtom(Polynomial.variable(0), PolynomialRelation.GE))
        val pending = preparePolynomialConjunction(atoms, PolynomialArithmetic(Cancellation { stopped }))
        assertNull(pending.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.asSequence().filterNotNull().single() }
    }

    @Test
    fun `containing work exhaustion withholds a completed conjunction refutation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (<= (- x0 x1) 0) (>= (- x0 x1) 0) (distinct x0 x1)))",
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, source.boolCount,
            source.realCount, QfNraParams(maximumWork = 100_000)))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
