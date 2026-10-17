package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.NEGATIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialSquareSignsTest {
    @Test
    fun `square enclosures retain a feasible zero boundary`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= (+ (* x x) (* y y) (* (- 2) x y)) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atom = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128)).single()

        val signs = preparePolynomialSquareSigns(atom.polynomial, emptyMap(), arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(ZERO_SIGN or POSITIVE_SIGN, signs)
    }

    @Test
    fun `a positive residue excludes zero beside a nonnegative weighted square`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const x Real) (declare-const y Real) " +
                "(assert (= (+ (* a x x) (* a y y) (* (- 2) a x y) 1) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atom = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128)).single()
        val variables = mapOf(0 to (ZERO_SIGN or POSITIVE_SIGN))

        val signs = preparePolynomialSquareSigns(atom.polynomial, variables, arithmetic)
            .asSequence().filterNotNull().single()

        assertEquals(POSITIVE_SIGN, signs)
    }

    @Test
    fun `a negative square weight retains negative values`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const x Real) (declare-const y Real) " +
                "(assert (= (+ (* a x x) (* a y y) (* (- 2) a x y)) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atom = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128)).single()
        val variables = mapOf(0 to NEGATIVE_SIGN, 1 to ALL_SIGNS, 2 to ALL_SIGNS)

        val signs = preparePolynomialSquareSigns(atom.polynomial, variables, arithmetic)
            .asSequence().filterNotNull().single()

        assertTrue(signs and NEGATIVE_SIGN != 0)
    }

    @Test
    fun `insufficient positive coefficients retain a negative remainder`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (= (+ (* 2 x x) (* 2 y y) (* (- 5) x y)) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atom = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128)).single()

        val signs = preparePolynomialSquareSigns(atom.polynomial, mapOf(0 to POSITIVE_SIGN, 1 to POSITIVE_SIGN),
            arithmetic).asSequence().filterNotNull().single()

        assertTrue(signs and NEGATIVE_SIGN != 0)
    }

    @Test
    fun `interruption withholds an unfinished square enclosure`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= (+ (* x x) (* y y) (* (- 2) x y)) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atom = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128)).single()
        var stopped = false
        val pending = preparePolynomialSquareSigns(atom.polynomial, emptyMap(),
            PolynomialArithmetic(Cancellation { stopped }))
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
