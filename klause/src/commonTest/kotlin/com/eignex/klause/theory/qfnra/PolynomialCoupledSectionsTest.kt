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
    fun `joint root choices refute an inconsistent cycle that each predicate satisfies`() {
        for (closing in listOf(-1, 1)) {
            val source = SmtLibPolynomial.parse("(declare-const x Real) (declare-const y Real) " +
                "(declare-const z Real) (assert (and (= (* x x) 1) (= (* y y) 1) (= (* z z) 1) " +
                "(= (* x y) 1) (= (* y z) 1) (= (* x z) $closing)))")
            val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
                .forcedAtoms(PolynomialArithmetic()))
            val single = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits(),
                prepareSupports = false, joint = false)
            val joint = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits(),
                prepareSupports = false)
            val identities = required.indices.map { it.toLong() }

            assertNull(single.refute(required, identities, HashSet()))
            val support = joint.refute(required, identities, HashSet())

            assertEquals(closing == -1, support != null)
            if (support != null) assertEquals(required.indices.toList(), support)
            assertEquals(1L, joint.stats.jointSupports)
        }
    }

    @Test
    fun `total encoded definitions retain every premise of a recovered support`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (declare-const y Real) " +
            "(declare-const u Real) (declare-const v Real) (declare-const w Real) " +
            "(assert (and (= u (* x x)) (= u 2) (= v (* y y)) (= v 3) " +
            "(= w (+ (* x y) x y)) (= w 1)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val direct = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits(), prepareSupports = false)
        val prepared = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())
        val identities = required.indices.map { it.toLong() }

        assertNull(direct.refute(required, identities, HashSet()))
        val support = prepared.refute(required, identities, HashSet())

        assertEquals(required.indices.toList(), support)
        assertEquals(1L, prepared.stats.preparedSupports)
    }

    @Test
    fun `a variable coefficient cannot supply a total substitution at its zero fibre`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (declare-const y Real) " +
            "(declare-const z Real) (assert (and (= (* x x) 1) (= (* y y) 1) " +
            "(= (* z (- (* x y) 2)) 0)))")
        val required = assertNotNull(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .forcedAtoms(PolynomialArithmetic()))
        val sections = PolynomialCoupledSections(QfNraWork(QfNraParams()), PolynomialLimits())

        val support = sections.refute(required, required.indices.map { it.toLong() }, HashSet())

        assertNull(support)
        assertEquals(0L, sections.stats.conflicts)
    }

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
