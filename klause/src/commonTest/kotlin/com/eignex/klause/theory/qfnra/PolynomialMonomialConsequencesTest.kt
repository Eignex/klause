package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialMonomialConsequencesTest {
    @Test
    fun `complete regions refute incompatible values of a shared monomial`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x1) 1)) (assert (< (* x0 x1) 0))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.projectedPolynomials)
        assertTrue(checkNotNull(refuted.stats.booleanTheoryRegions) > 0)
    }

    @Test
    fun `complete regions combine nonzero factors and square remainders`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const p$it Real)" }
        val source = SmtLibPolynomial.parse(
            """
            $declarations
            (declare-const a Real) (declare-const b Real) (declare-const w Real)
            (declare-const u Real) (declare-const x Real)
            (assert (and (> a 0) (> b 0) (= (* a (- w (* x x))) 0)
              (= (+ (* a w) (* b u) (- (* a x x))) 0)
              (= (+ a (* x x) (* (- 2) x) 1 u) 0)))
            """.trimIndent(),
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 50_000))

        val refuted = assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(0L, refuted.stats.dyadicAttempts)
        assertEquals(0L, refuted.stats.projectedPolynomials)
    }

    @Test
    fun `a monomial identity leaves nondivisible coordinates unchanged`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const x Real) (assert (and (= (* a x) 1) (> a 0) (> x 2)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMonomialRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertFalse(refuted)
    }

    @Test
    fun `multiple square pairs retain a strictly positive residue`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const d Real) (declare-const t Real) (declare-const y Real) (declare-const c Real)
            (assert (and (>= d 0) (>= t 0) (>= y 0) (> c 0)
              (= (+ (* d d t y) (* t y) (* d t t y y) d (* d d) (* t t y y)
                (* (- 6) d t y) c) 0)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMonomialRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertTrue(refuted)
    }

    @Test
    fun `unknown zero factors retain feasible singular values`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Real) (declare-const x Real) (assert (and (= (* a x) 0) (> x 1) (>= a 0)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val refuted = preparePolynomialMonomialRefutation(atoms, arithmetic).asSequence().filterNotNull().single()

        assertFalse(refuted)
    }

    @Test
    fun `monomial substitutions retain kernel degree representation limits`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (= (* x y) (* x x))) (assert (= (+ (* (^ x 2147483647) y) 1) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))

        val failure = assertFailsWith<PolynomialResourceLimit> {
            preparePolynomialMonomialRefutation(atoms, arithmetic).asSequence().filterNotNull().single()
        }

        assertEquals(PolynomialResource.DEGREE_OVERFLOW, failure.resource)
    }

    @Test
    fun `interruption withholds an unfinished monomial consequence`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= (* x y) 1)) (assert (< (* x y) 0))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val atoms = assertNotNull(formula.conjunctiveAtoms(arithmetic, 128))
        var stopped = false
        val pending = preparePolynomialMonomialRefutation(atoms, PolynomialArithmetic(Cancellation { stopped }))
        assertNull(pending.next())
        stopped = true

        assertFailsWith<PolynomialInterrupted> { pending.next() }
    }

    @Test
    fun `containing work exhaustion withholds a completed regional consequence`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (= (* x0 x1) 1)) (assert (< (* x0 x1) 0))",
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(
            source.formula, source.boolCount, source.realCount, QfNraParams(maximumWork = 50_000),
        ))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
