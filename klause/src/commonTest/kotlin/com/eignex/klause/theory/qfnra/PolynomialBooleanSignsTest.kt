package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialBooleanSignsTest {
    @Test
    fun `globally true numeric alternatives need no projection boundaries`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (or b (>= (+ (* x0 x0) (* x1 x1)) 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(polynomialLimits = PolynomialLimits(maxMatrixCells = 0)))

        assertIs<QfNraResult.Satisfiable>(result)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `captured sign consequences retain the numeric premises needed for source truth`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (> x 0) (= y (* x x)) (= b (> y 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val region = formula.booleanRegions(arithmetic).asSequence().filterNotNull().single()

        assertEquals(mapOf(0 to true), region.bools)
        assertEquals(setOf(0, 1), region.atoms.keys)
        assertTrue(region.atoms.values.all { it })
    }

    @Test
    fun `required polynomial definitions resolve their Boolean sign before regions`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (> x 0) (= y (* x x)) (= b (> y 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(mapOf(0 to true), regions.single().bools)
    }

    @Test
    fun `false required comparisons retain their complemented sign`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (not (>= x 0)) (= y (- x)) (= b (> y 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(mapOf(0 to true), regions.single().bools)
    }

    @Test
    fun `weak sign bounds retain the zero and positive Boolean alternatives`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (>= x 0) (= y (* x x)) (= b (> y 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(setOf(false, true), regions.map { it.bools.getValue(0) }.toSet())
    }

    @Test
    fun `Boolean sign consequences expose further required polynomial rows`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const z Real) " +
                "(declare-const a Bool) (declare-const b Bool) " +
                "(assert (and (> x 0) (= y (* x x)) (= a (> y 0)) " +
                "(=> a (= z (* y y))) (= b (> z 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val regions = formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(mapOf(0 to true, 1 to true), regions.single().bools)
    }

    @Test
    fun `retracted pins discard sign consequences from guarded definitions`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) " +
                "(assert (or (and b (> x0 0) (= x1 (* x0 x0))) " +
                "(and (not b) (= x0 0) (= x1 0))))",
        )
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            for (pin in listOf(true, false, true)) {
                val result = invocation.check(PolynomialCondition(bools = mapOf(0 to pin)))

                val witness = assertIs<QfNraResult.Satisfiable>(result).assignment
                val sign = AlgebraicArithmetic().sign(witness.realValue(1))
                assertEquals(pin, witness.boolValue(0))
                if (pin) assertTrue(sign > 0) else assertEquals(0, sign)
            }
        }
    }

    @Test
    fun `interrupted initial sign propagation publishes no region`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (> x 0) (= y (* x x)) (= b (> y 0))))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val regions = formula.booleanRegions(PolynomialArithmetic(Cancellation { stopped }))
        assertNull(regions.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { regions.next() }
    }

    @Test
    fun `initial sign consequences cannot renew containing work for publication`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) " +
                "(assert (and (> x0 0) (= x1 (* x0 x0)) (= b (> x1 0))))",
        )
        val complete = solveQfNra(source.formula, source.boolCount, source.realCount)
        assertIs<QfNraResult.Satisfiable>(complete)

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = complete.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
