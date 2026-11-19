package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolynomialLpRelaxationTest {
    @Test
    fun `bounded bilinear envelopes should refute an impossible product before projection`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const y Real)
            (assert (and (>= x 2) (<= x 3) (>= y 2) (<= y 3) (<= (* x y) 3)))
        """.trimIndent())

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Unsatisfiable>(result)
        assertEquals(1L, result.stats.envelopeRefutations)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `square refinement should refute a coupled convex region`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const y Real)
            (assert (and (>= x 0) (<= x 1) (>= y 0) (<= y 1)
                (= (+ x y) 1) (<= (+ (* x x) (* y y)) (/ 2 5))))
        """.trimIndent())

        val work = QfNraWork(QfNraParams(maximumWork = 100_000))
        val arithmetic = PolynomialArithmetic(work).also { work.execution = it.execution }
        val atoms = requireNotNull(PolynomialFormula.normalize(source.formula, arithmetic).forcedAtoms(arithmetic))
        val result = PolynomialLpPruning(atoms, source.realCount, arithmetic, work).use { pruning ->
            var step = pruning.advance { null }
            while (step == PolynomialLpStep.Pending) step = pruning.advance { null }
            step
        }

        assertIs<PolynomialLpStep.Refuted>(result)
        assertEquals(1L, work.envelopeRefutations)
        assertTrue(work.envelopeRefinements > 0)
        assertEquals(0L, work.projectedPolynomials)
    }

    @Test
    fun `LP feasibility should reject an invalid nonlinear proposal`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (assert (and (>= x (/ 1 2)) (<= x (/ 3 2)) (= (* x x) 1)))
        """.trimIndent())
        val work = QfNraWork(QfNraParams())
        val arithmetic = PolynomialArithmetic(work).also { work.execution = it.execution }
        val atoms = requireNotNull(PolynomialFormula.normalize(source.formula, arithmetic).forcedAtoms(arithmetic))
        val proposal = requireNotNull(PolynomialLpRelaxation.prepare(atoms, 1, arithmetic, work)).use {
            requireNotNull(it.solve()?.exactPrimal)
        }
        assertTrue(proposal[0] * proposal[0] != BigFraction.ONE)

        val result = solveQfNra(source.formula, source.boolCount, source.realCount)

        assertIs<QfNraResult.Satisfiable>(result)
        val algebraic = AlgebraicArithmetic()
        assertEquals(0, algebraic.compare(algebraic.rational(BigFraction.ONE), result.assignment.realValue(0)))
    }

    @Test
    fun `a declined envelope matrix should preserve exact algebraic completion`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")

        val result = solveQfNra(source.formula, 0, 1,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Satisfiable>(result)
        val algebraic = AlgebraicArithmetic()
        val value = result.assignment.realValue(0)
        assertEquals(0, algebraic.compare(algebraic.multiply(value, value), algebraic.rational(BigFraction.ofLong(2))))
        assertEquals(0L, result.stats.envelopeRefutations)
    }

    @Test
    fun `an LP proposal should restore substituted query coordinates`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const y Real)
            (assert (= (* y y) 1))
        """.trimIndent())
        val fixed = mapOf(0 to BigFraction.ofLong(7))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            condition = PolynomialCondition(lower = fixed, upper = fixed))

        assertIs<QfNraResult.Satisfiable>(result)
        assertEquals(BigFraction.ofLong(7), result.assignment.realValue(0).rational)
    }

    @Test
    fun `one sided square should bound its result without inventing an upper endpoint`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (>= r 3) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        val bounds = service.invocation().use { it.bounds(PolynomialCondition(), setOf(0)) }

        assertEquals(BigFraction.ofLong(9), bounds.lower[0])
    }

    @Test
    fun `an equality tolerance band should weaken the square consequence`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (>= r 3)
                (>= (- (* r r) x) (- 2)) (<= (- (* r r) x) 2)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        val bounds = service.invocation().use { it.bounds(PolynomialCondition(), setOf(0)) }

        assertEquals(BigFraction.ofLong(7), bounds.lower[0])
    }

    @Test
    fun `a widened query should discard conditioned envelope bounds`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            val restricted = invocation.bounds(PolynomialCondition(lower = mapOf(1 to BigFraction.ofLong(3))), setOf(0))
            assertEquals(BigFraction.ofLong(9), restricted.lower[0])

            val widened = invocation.bounds(PolynomialCondition(), setOf(0))

            assertEquals(BigFraction.ZERO, widened.lower[0])
        }
    }

    @Test
    fun `deduction work should spend the same cumulative caller allowance as completion`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (>= r 3) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var charged = 0L
        val token = ExecutionControl(accounting = WorkMeter { charged += it })

        service.invocation(QfNraParams(cancellation = token)).use { invocation ->
            invocation.bounds(PolynomialCondition(), setOf(0))
            invocation.check()

            assertTrue(charged > 0)
            assertEquals(invocation.stats.workSpent, charged)
        }
    }

    @Test
    fun `exhausted deduction should publish no partial bounds`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const r Real)
            (assert (and (>= x 0) (<= x 10) (>= r 3) (= (* r r) x)))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation(QfNraParams(maximumWork = 10)).use { invocation ->
            val bounds = invocation.bounds(PolynomialCondition(), setOf(0))

            assertTrue(bounds.stopped)
            assertTrue(bounds.lower.isEmpty() && bounds.upper.isEmpty())
            assertEquals(10L, invocation.stats.workSpent)
        }
    }
}
