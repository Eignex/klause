package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialQuadraticRefutationTest {
    @Test
    fun `affine separation from an ellipsoid has an exact quadratic refutation`() {
        for (quadratic in listOf(
            "(<= (+ (* x x) (* x y) (* y y)) 1)",
            "(>= (- 1 (+ (* x x) (* x y) (* y y))) 0)",
            "(= (- 1 (+ (* x x) (* x y) (* y y))) 0)",
            "(<= (+ (* (- x 1) (- x 1)) (* (- x 1) (+ y 2)) (* (+ y 2) (+ y 2))) 1)",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) " +
                    "(assert (and $quadratic (>= (+ (* 2 x) y) 3)))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

            val refuted = preparePolynomialQuadraticRefutation(atoms, arithmetic)
                .asSequence().filterNotNull().single()

            assertTrue(refuted)
        }
    }

    @Test
    fun `overlapping affine and quadratic predicates retain feasibility`() {
        for ((quadratic, affine) in listOf(
            "(<= (+ (* x x) (* y y)) 1)" to "(>= x 0)",
            "(<= (+ (* x x) (* y y)) 1)" to "(<= x 2)",
            "(<= (- (* y y) (* x x)) (- 1))" to "(>= x 0)",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert (and $quadratic $affine))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

            val refuted = preparePolynomialQuadraticRefutation(atoms, arithmetic)
                .asSequence().filterNotNull().single()

            assertFalse(refuted)
        }
    }

    @Test
    fun `strict tangency requires a strict quadratic or positive affine multiplier`() {
        for ((quadratic, affine, expected) in listOf(
            Triple("(<= (+ (* x x) (* y y)) 1)", "(> x 1)", true),
            Triple("(< (+ (* x x) (* y y)) 1)", "(>= x 1)", true),
            Triple("(<= (+ (* x x) (* y y)) 1)", "(>= x 1)", false),
            Triple("(<= (+ (* x x) (* y y)) 0)", "(< x 1)", false),
            Triple("(<= (+ (* x x) (* y y)) 0)", "(< x 0)", false),
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert (and $quadratic $affine))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

            val refuted = preparePolynomialQuadraticRefutation(atoms, arithmetic)
                .asSequence().filterNotNull().single()

            assertEquals(expected, refuted)
        }
    }

    @Test
    fun `singular convex quadratics admit exact equality certificates`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (<= (* (+ x y) (+ x y)) 1) (= (+ x y) 2)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

        val refuted = preparePolynomialQuadraticRefutation(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertTrue(refuted)
    }

    @Test
    fun `affine variables outside quadratic support leave the bound unconstrained`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (<= (* x x) 1) (>= (+ x y) 3)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

        val refuted = preparePolynomialQuadraticRefutation(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(refuted)
    }

    @Test
    fun `matrix refusal withholds a quadratic refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (and (<= (* x x) 1) (>= x 2)))",
        )
        val arithmetic = PolynomialArithmetic(limits = PolynomialLimits(maxMatrixCells = 0))
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))

        val refuted = preparePolynomialQuadraticRefutation(atoms, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(refuted)
    }

    @Test
    fun `quadratic certificate preparation observes caller cancellation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (and (<= (* x x) 1) (>= x 2)))",
        )
        var cancelled = false
        val arithmetic = PolynomialArithmetic(Cancellation { cancelled })
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, Int.MAX_VALUE))
        val preparing = preparePolynomialQuadraticRefutation(atoms, arithmetic)
        preparing.next()

        cancelled = true

        assertFailsWith<PolynomialInterrupted> { preparing.asSequence().filterNotNull().single() }
    }

    @Test
    fun `regional graph reduction exposes an affine quadratic contradiction`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (<= (+ (* x0 x0) (* x1 x1)) 1) " +
                "(= x2 (+ (* 2 x0) x1)) (>= x2 3)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.projectedPolynomials)
    }

    @Test
    fun `a quadratic conflict stays inside its Boolean region`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (or (and (<= (+ (* x0 x0) (* x1 x1)) 1) (>= x0 2)) (= x0 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 100_000))

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ZERO, witness.realValue(0).rational)
    }
}
