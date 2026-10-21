package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialRequiredAtomsTest {
    @Test
    fun `newly constant predicates expose further required numeric facts`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(assert (and (= x 0) (or (> x 0) (= y 1))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val required = formula.requiredAtoms(arithmetic)
        val expected = formula.atoms.single { 1 in it.polynomial.variables }
        val before = assertNotNull(formula.forcedAtoms(required))
        val rewritten = formula.withAtoms(formula.atoms.map {
            PolynomialAtom(arithmetic.substitute(it.polynomial, 0, Polynomial.ZERO), it.relation)
        })

        val after = assertNotNull(rewritten.forcedAtoms(required))

        assertFalse(expected in before)
        assertTrue(expected in after)
        assertEquals(rewritten.forcedAtoms(rewritten.requiredAtoms(arithmetic)), after)
    }

    @Test
    fun `a completed required set fits the remaining caller work`() {
        val declarations = (0..127).joinToString(" ") { "(declare-const b$it Bool)" }
        val clauses = (0 until 127).joinToString(" ") { "(or b$it b${it + 1})" }
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) $declarations (assert (and (= x 0) $clauses))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val arithmetic = PolynomialArithmetic(cancellation)
        val required = formula.requiredAtoms(arithmetic)
        val expected = assertNotNull(formula.forcedAtoms(required))
        limit = spent + 64

        val actual = formula.forcedAtoms(required)

        assertEquals(expected, actual)
    }

    @Test
    fun `unrelated Boolean captures cannot share required facts`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 0))")
        val other = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val unrelated = PolynomialFormula.normalize(other.formula, arithmetic)
        val required = formula.requiredAtoms(arithmetic)
        val expected = formula.forcedAtoms(required)

        assertFailsWith<IllegalArgumentException> { unrelated.forcedAtoms(required) }

        assertEquals(expected, formula.forcedAtoms(required))
    }

    @Test
    fun `a changed captured constant rejects stale required facts`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (or (= x 0) b))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        val constant = formula.withAtoms(formula.atoms.map { PolynomialAtom(Polynomial.ZERO, it.relation) })
        val required = constant.requiredAtoms(arithmetic)
        assertNotNull(constant.forcedAtoms(required))
        val changed = constant.withAtoms(constant.atoms.map { PolynomialAtom(Polynomial.ONE, it.relation) })

        assertFailsWith<IllegalArgumentException> { changed.forcedAtoms(required) }

        assertNotNull(changed.forcedAtoms(changed.requiredAtoms(arithmetic)))
    }

    @Test
    fun `completed required facts still obey containing cancellation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 0))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val required = formula.requiredAtoms(arithmetic)
        val expected = formula.forcedAtoms(required)
        stopped = true

        assertFailsWith<PolynomialInterrupted> { formula.forcedAtoms(required) }

        stopped = false
        assertEquals(expected, formula.forcedAtoms(formula.requiredAtoms(arithmetic)))
    }

    @Test
    fun `an interrupted transition retains the fresh closure on retry`() {
        val declarations = (0..63).joinToString(" ") { "(declare-const b$it Bool)" }
        val clauses = (0..63).joinToString(" ") { "(or (> x 0) b$it)" }
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) $declarations " +
                "(assert (and $clauses (or (not b63) (= y 1))))",
        )
        val plain = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, plain)
        val rewritten = formula.withAtoms(formula.atoms.map {
            PolynomialAtom(plain.substitute(it.polynomial, 0, Polynomial.ZERO), it.relation)
        })
        var spent = 0L
        var limit = Long.MAX_VALUE
        val meter = WorkMeter { spent += it }
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = spent >= limit
            override fun workMeter(): WorkMeter = meter
        }
        val arithmetic = PolynomialArithmetic(cancellation)
        val baseline = formula.requiredAtoms(arithmetic)
        assertNotNull(formula.forcedAtoms(baseline))
        val start = spent
        val expected = assertNotNull(rewritten.forcedAtoms(baseline))
        val transitionWork = spent - start

        for (fraction in 1..4) {
            val required = formula.requiredAtoms(arithmetic)
            assertNotNull(formula.forcedAtoms(required))
            limit = spent + maxOf(1L, transitionWork * fraction / 5)

            assertFailsWith<PolynomialInterrupted> { rewritten.forcedAtoms(required) }

            limit = Long.MAX_VALUE
            assertEquals(expected, rewritten.forcedAtoms(required))
        }
    }

    @Test
    fun `a captured constant contradiction withholds its result after cancellation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val required = formula.requiredAtoms(arithmetic)
        assertNotNull(formula.forcedAtoms(required))
        val rewritten = formula.withAtoms(formula.atoms.map { PolynomialAtom(Polynomial.ZERO, it.relation) })
        assertNull(rewritten.forcedAtoms(required))
        assertNull(rewritten.forcedAtoms(required))
        stopped = true

        assertFailsWith<PolynomialInterrupted> { rewritten.forcedAtoms(required) }
    }
}
