package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialMagnitudePropagationTest {
    @Test
    fun `complete regions refute products beyond necessary even power magnitudes`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (< (+ (* x0 x0) (* x1 x1) (* x2 x2)) 1)) " +
                "(assert (> (* x0 x1 x2) 1))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.projectedPolynomials)
        assertTrue(checkNotNull(refuted.stats.booleanTheoryRegions) > 0)
    }

    @Test
    fun `closed magnitude endpoints retain equality at the boundary`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (<= (* x x) 1)) (assert (>= x 1))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMagnitudeRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertEquals(false, refuted)
    }

    @Test
    fun `zero ceilings force each nonnegative summand to zero`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (<= (+ (* x x) (^ y 4)) 0)) (assert (distinct x 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMagnitudeRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertEquals(true, refuted)
    }

    @Test
    fun `opposite signed even powers do not supply a magnitude ceiling`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (<= (- (* x x) (* y y)) 1)) (assert (> (* x y) 2))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMagnitudeRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertEquals(false, refuted)
    }

    @Test
    fun `a missing coordinate magnitude withholds a product refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (<= (* x x) 1)) (assert (> (* x y) 2))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMagnitudeRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertEquals(false, refuted)
    }

    @Test
    fun `negative orientations retain rational magnitude consequences`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (>= (- 4 (+ (^ x 4) (* y y))) 0)) (assert (> (* x y) 16))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMagnitudeRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertEquals(true, refuted)
    }

    @Test
    fun `a magnitude refuted region leaves a feasible Boolean alternative`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (<= (+ (* x0 x0) (* x1 x1)) 1) (> (* x0 x1) 1)) " +
                "(and (= x0 1) (= x1 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.compare(witness.realValue(0), algebraic.rational(BigFraction.ONE)))
        assertEquals(0, algebraic.sign(witness.realValue(1)))
    }

    @Test
    fun `magnitude enclosures retain kernel power representation limits`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (<= (* x x) 4)) (assert (> (^ x 2147483647) 1))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        val pending = preparePolynomialMagnitudeRefutation(atoms, arithmetic)

        val failure = assertFailsWith<PolynomialResourceLimit> { pending.asSequence().filterNotNull().single() }

        assertEquals(PolynomialResource.DEGREE_OVERFLOW, failure.resource)
    }

    @Test
    fun `interruption after magnitude bounds withholds the refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (<= (* x x) 1)) (assert (> x 2))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        var stopped = false
        val interrupted = PolynomialArithmetic(Cancellation { stopped })
        val pending = preparePolynomialMagnitudeRefutation(atoms, interrupted)
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }
}
