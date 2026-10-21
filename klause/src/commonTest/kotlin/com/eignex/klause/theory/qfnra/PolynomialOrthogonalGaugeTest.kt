package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialOrthogonalGaugeTest {
    @Test
    fun `coupled isotropic predicates admit weak triangular representatives`() {
        val arithmetic = PolynomialArithmetic()
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real) " +
                "(assert (= (+ (* a a) (* b b)) 1)) (assert (= (+ (* c c) (* d d)) 1)) " +
                "(assert (<= (+ (* a c) (* b d)) 0))",
        )
        val atoms = PolynomialFormula.normalize(source.formula, arithmetic).atoms

        val constraints = preparePolynomialOrthogonalGauge(atoms, emptySet(), arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(listOf(PolynomialAtom(Polynomial.variable(1), PolynomialRelation.EQ),
            PolynomialAtom(Polynomial.variable(0), PolynomialRelation.GE),
            PolynomialAtom(Polynomial.variable(3), PolynomialRelation.GE)), constraints)
    }

    @Test
    fun `noninvariant regional predicates withhold a symmetry certificate`() {
        for (predicate in listOf("(+ (* a c) (* b d) a)", "(+ (* a c) (* 2 b d))",
            "(+ (* a d) (* b c))", "(* a a c c)", "(* a c e)")) {
            val arithmetic = PolynomialArithmetic()
            val source = SmtLibPolynomial.parse(
                "(declare-const a Real) (declare-const b Real) (declare-const c Real) " +
                    "(declare-const d Real) (declare-const e Real) " +
                    "(assert (= (+ (* a a) (* b b)) 1)) (assert (= (+ (* c c) (* d d)) 1)) " +
                    "(assert (<= $predicate 0))",
            )
            val atoms = PolynomialFormula.normalize(source.formula, arithmetic).atoms

            val constraints = preparePolynomialOrthogonalGauge(atoms, emptySet(), arithmetic)
                .asSequence().filterNotNull().single()

            assertTrue(constraints.isEmpty(), predicate)
        }
    }

    @Test
    fun `unrotated polynomial coefficients preserve a common orthogonal action`() {
        val arithmetic = PolynomialArithmetic()
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (declare-const c Real) " +
                "(declare-const d Real) (declare-const e Real) " +
                "(assert (<= (+ (* a a) (* b b)) 1)) (assert (>= (+ (* c c) (* d d)) 1)) " +
                "(assert (> (* (+ (* e e) 1) (+ (* a c) (* b d))) e))",
        )
        val atoms = PolynomialFormula.normalize(source.formula, arithmetic).atoms

        val constraints = preparePolynomialOrthogonalGauge(atoms, emptySet(), arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(listOf(PolynomialAtom(Polynomial.variable(1), PolynomialRelation.EQ),
            PolynomialAtom(Polynomial.variable(0), PolynomialRelation.GE),
            PolynomialAtom(Polynomial.variable(3), PolynomialRelation.GE)), constraints)
    }

    @Test
    fun `fixed coordinates withhold rotations that would move them`() {
        for (fixed in 0 until 4) {
            val arithmetic = PolynomialArithmetic()
            val source = SmtLibPolynomial.parse(
                "(declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real) " +
                    "(assert (= (+ (* a a) (* b b)) 1)) (assert (= (+ (* c c) (* d d)) 1)) " +
                    "(assert (<= (+ (* a c) (* b d)) 0))",
            )
            val atoms = PolynomialFormula.normalize(source.formula, arithmetic).atoms

            val constraints = preparePolynomialOrthogonalGauge(atoms, setOf(fixed), arithmetic)
                .asSequence().filterNotNull().single()

            assertTrue(constraints.isEmpty())
        }
    }

    @Test
    fun `uncoupled norms retain their original representation`() {
        val arithmetic = PolynomialArithmetic()
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real) " +
                "(assert (= (+ (* a a) (* b b)) 1)) (assert (= (+ (* c c) (* d d)) 1))",
        )
        val atoms = PolynomialFormula.normalize(source.formula, arithmetic).atoms

        val constraints = preparePolynomialOrthogonalGauge(atoms, emptySet(), arithmetic)
            .asSequence().filterNotNull().single()

        assertTrue(constraints.isEmpty())
    }

    @Test
    fun `interrupted coefficient certification publishes no gauge constraints`() {
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const b Real) (declare-const c Real) (declare-const d Real) " +
                "(assert (= (+ (* a a) (* b b)) 1)) (assert (= (+ (* c c) (* d d)) 1)) " +
                "(assert (<= (+ (* a c) (* b d)) 0))",
        )
        val atoms = PolynomialFormula.normalize(source.formula, arithmetic).atoms
        val pending = preparePolynomialOrthogonalGauge(atoms, emptySet(), arithmetic)
        repeat(atoms.size) { assertNull(pending.next()) }
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
