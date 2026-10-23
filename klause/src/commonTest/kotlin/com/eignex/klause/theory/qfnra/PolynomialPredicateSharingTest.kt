package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialPredicateSharingTest {
    @Test
    fun `Boolean definition substitution retains numeric predicate order`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (or b (> y 0)) (= b (> x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduced = assertNotNull(formula.reduceBooleanDefinitions(arithmetic))

        assertEquals(formula.atoms, reduced.atoms)
    }

    @Test
    fun `partial numeric rewrites retain equivalent predicate truth`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (= b (> x 0)) (= c (> y 0)) (not (= b c))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val replacements = formula.atoms.map { atom ->
            if (atom.polynomial.variables.singleOrNull() == 1) {
                PolynomialAtom(arithmetic.scale(formula.atoms.first().polynomial, BigFraction.ofLong(3)), atom.relation)
            } else atom
        }

        val rewritten = formula.withAtoms(replacements, arithmetic)

        assertTrue(rewritten.refuteBooleans(arithmetic, 128, 2048, 4096))
    }

    @Test
    fun `constant rewrites retain strict comparison truth at zero`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (>= x 0) (> y 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val replacements = formula.atoms.map { atom ->
            if (atom.polynomial.variables.singleOrNull() == 1) PolynomialAtom(Polynomial.ZERO, atom.relation) else atom
        }

        val rewritten = formula.withAtoms(replacements, arithmetic)

        assertNull(rewritten.forcedAtoms(arithmetic))
    }

    @Test
    fun `predicate resharing observes cancellation in its original arithmetic context`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        stopped = true

        assertFailsWith<PolynomialInterrupted> { formula.withAtoms(formula.atoms, arithmetic) }
    }

    @Test
    fun `a different arithmetic context pays for predicate normalization`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (< (+ (* 2 x x) (* 3 x y) (* 5 y y) (* 7 x) (* 11 y) 13) 0))",
        )
        var originalWork = 0L
        val originalMeter = WorkMeter { originalWork += it }
        val arithmetic = PolynomialArithmetic(object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = originalMeter
        })
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val before = originalWork
        formula.withAtoms(formula.atoms, arithmetic)
        val reusedWork = originalWork - before
        var otherWork = 0L
        val otherMeter = WorkMeter { otherWork += it }
        val other = PolynomialArithmetic(object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = otherMeter
        })

        formula.withAtoms(formula.atoms, other)

        assertTrue(otherWork > reusedWork)
    }
}
