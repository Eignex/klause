package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialSignPropagationTest {
    @Test
    fun `a sign conflict reports only its connected numeric premises`() {
        for (relation in listOf(PolynomialRelation.LT, PolynomialRelation.GT)) {
            val arithmetic = PolynomialArithmetic()
            val definition = arithmetic.subtract(Polynomial.variable(1), arithmetic.power(Polynomial.variable(0), 2))
            val atoms = listOf(PolynomialAtom(Polynomial.variable(0), relation),
                PolynomialAtom(Polynomial.variable(2), PolynomialRelation.GE),
                PolynomialAtom(definition, PolynomialRelation.EQ),
                PolynomialAtom(Polynomial.variable(1), PolynomialRelation.LT))
            var core: List<Int>? = null

            val refuted = preparePolynomialSignRefutation(atoms, 3, arithmetic) { core = it }
                .asSequence().filterNotNull().single()

            assertTrue(refuted)
            assertEquals(listOf(0, 2, 3), core)
        }
    }

    @Test
    fun `a zero fiber conflict retains every premise excluding a strict sign`() {
        for (relation in listOf(PolynomialRelation.LT, PolynomialRelation.GT)) {
            val arithmetic = PolynomialArithmetic()
            val definition = arithmetic.subtract(Polynomial.variable(1), arithmetic.power(Polynomial.variable(0), 2))
            val atoms = listOf(PolynomialAtom(definition, PolynomialRelation.EQ),
                PolynomialAtom(Polynomial.variable(2), PolynomialRelation.NE),
                PolynomialAtom(Polynomial.variable(1), PolynomialRelation.LE),
                PolynomialAtom(Polynomial.variable(0), relation))
            var core: List<Int>? = null

            val refuted = preparePolynomialSignRefutation(atoms, 3, arithmetic) { core = it }
                .asSequence().filterNotNull().single()

            assertTrue(refuted)
            assertEquals(listOf(0, 2, 3), core)
        }
    }

    @Test
    fun `feasible sign propagation reports no conflict core`() {
        val arithmetic = PolynomialArithmetic()
        val definition = arithmetic.subtract(Polynomial.variable(1), arithmetic.power(Polynomial.variable(0), 2))
        val atoms = listOf(PolynomialAtom(definition, PolynomialRelation.EQ),
            PolynomialAtom(Polynomial.variable(1), PolynomialRelation.LE),
            PolynomialAtom(Polynomial.variable(0), PolynomialRelation.EQ))
        val cores = ArrayList<List<Int>>()

        val refuted = preparePolynomialSignRefutation(atoms, 2, arithmetic) { cores.add(it) }
            .asSequence().filterNotNull().single()

        assertEquals(false, refuted)
        assertTrue(cores.isEmpty())
    }

    @Test
    fun `complete regions propagate strict signs through polynomial definitions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val equations = (1..16).joinToString(" ") { "(assert (= x$it (* x${it - 1} x${it - 1})))" }
        val source = SmtLibPolynomial.parse("$declarations $equations (assert (> x0 0)) (assert (= x16 0))")

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.equationalSections)
    }

    @Test
    fun `nonpositive even power outputs retain their zero fiber`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= x1 (* x0 x0))) (assert (<= x1 0)) (assert (= x0 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `opposite signed terms can cancel in a required zero`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (> x0 0)) (assert (< x1 0)) " +
                "(assert (= x2 (+ x0 x1))) (assert (= x2 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness.realValue(0)) > 0)
        assertTrue(algebraic.sign(witness.realValue(1)) < 0)
        assertEquals(0, algebraic.sign(algebraic.add(witness.realValue(0), witness.realValue(1))))
    }

    @Test
    fun `negative coefficients retain even power contradiction direction`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (< x0 0)) (assert (= x1 (- (* x0 x0)))) (assert (>= x1 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `a sign refuted region leaves a feasible Boolean alternative`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (> x0 0) (= x1 (* x0 x0)) (< x1 0)) " +
                "(and (= x0 0) (= x1 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.sign(witness.realValue(0)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `interruption during sign propagation withholds the refutation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0)) (assert (= (* x x) 0))")
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        var stopped = false
        val interrupted = PolynomialArithmetic(Cancellation { stopped })
        val cores = ArrayList<List<Int>>()
        val pending = preparePolynomialSignRefutation(atoms, source.realCount, interrupted) { cores.add(it) }
        repeat(3) { assertNull(pending.next()) }

        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
        assertTrue(cores.isEmpty())
    }
}
