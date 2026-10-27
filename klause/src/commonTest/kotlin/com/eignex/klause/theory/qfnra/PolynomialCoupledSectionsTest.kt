package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialCoupledSectionsTest {
    @Test
    fun `a coupled equation failing at every root combination refutes its whole support`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (= (* x1 x1) 2) (= (* x2 x2) 3) (= (+ (* x1 x2) x1 x2) 1)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

        val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

        assertEquals(listOf(0, 1, 2), support)
        assertEquals(4L, sections.stats.combinations)
        assertEquals(1L, sections.stats.conflicts)
    }

    @Test
    fun `strict weak and disequal coupled predicates keep their exact root boundary`() {
        for ((predicate, refuted) in listOf(
            "(> (* x1 x2) 2)" to true,
            "(>= (* x1 x2) 2)" to false,
            "(distinct (* x1 x2) 2)" to false,
            "(= (* x1 x2) 0)" to true,
        )) {
            val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
                "(assert (and (= (* x1 x1) 4) (= (* x2 x2) 1) $predicate))")
            val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
                .forcedAtoms(PolynomialArithmetic()))
            val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

            val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

            assertEquals(refuted, support != null, predicate)
            assertEquals(1L, sections.stats.supports, predicate)
        }
    }

    @Test
    fun `a repeated univariate root contributes its single section`() {
        for ((product, refuted) in listOf(2 to false, 1 to true)) {
            val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
                "(assert (and (= (+ (* x1 x1) (* (- 2) x1) 1) 0) (= (* x2 x2) 4) (= (* x1 x2) $product)))")
            val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
                .forcedAtoms(PolynomialArithmetic()))
            val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

            val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

            assertEquals(refuted, support != null)
            assertEquals(2L, sections.stats.combinations)
        }
    }

    @Test
    fun `an equation without real roots refutes without its coupled predicate`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (> (* x1 x2) 0) (= (* x1 x1) (- 1)) (= (* x2 x2) 1)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

        val support = assertNotNull(sections.refute(required, required.indices.map { it.toLong() }, HashSet()))

        assertEquals(listOf(listOf(source.realVarNames.getValue("x1"))),
            support.map { required[it].polynomial.variables })
        assertEquals(0L, sections.stats.combinations)
    }

    @Test
    fun `an uncovered coupled coordinate learns nothing`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (= (* x1 x1) 2) (= (* x1 x2) 1)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

        val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

        assertNull(support)
        assertEquals(0L, sections.stats.supports)
    }

    @Test
    fun `an attempted support is not evaluated again in the same scope`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (= (* x1 x1) 2) (= (* x2 x2) 3) (> (* x1 x2) 0)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())
        val checked = HashSet<List<Long>>()
        val identities = required.indices.map { it.toLong() }
        assertNull(sections.refute(required, identities, checked))
        val combinations = sections.stats.combinations

        assertNull(sections.refute(required, identities, checked))

        assertEquals(combinations, sections.stats.combinations)
        assertEquals(1L, sections.stats.repeatedSupports)
    }

    @Test
    fun `exhausting the private allowance declines while the containing query continues`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (= (* x1 x1) 2) (= (* x2 x2) 3) (= (+ (* x1 x2) x1 x2) 1)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val parent = QfNraWork(QfNraParams())
        val sections = PolynomialCoupledSections(parent, PolynomialLimits(), maximumWork = 16)

        val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

        assertNull(support)
        assertEquals(1L, sections.stats.declined)
        assertFalse(sections.available)
        assertFalse(parent.isCancelled())
        assertEquals(sections.stats.work, parent.spentWork)
    }

    @Test
    fun `an enclosing stop interrupts the check without a support`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (= (* x1 x1) 2) (= (* x2 x2) 3) (= (+ (* x1 x2) x1 x2) 1)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        var cancelled = false
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams(Cancellation { cancelled })),
            PolynomialLimits())
        cancelled = true

        assertFailsWith<PolynomialInterrupted> {
            sections.refute(required, required.indices.map { it.toLong() }, HashSet())
        }
        assertEquals(0L, sections.stats.conflicts)
    }

    @Test
    fun `a dense representation refusal declines the support`() {
        val source = SmtLibPolynomial.parse("(declare-const x1 Real) (declare-const x2 Real) " +
            "(assert (and (= (* x1 x1) 2) (= (* x2 x2) 2) (= (* x1 x2) 2)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()),
            PolynomialLimits(maxDenseCoefficients = 1))

        val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

        assertNull(support)
        assertEquals(1L, sections.stats.declined)
        assertTrue(sections.available)
    }
}
