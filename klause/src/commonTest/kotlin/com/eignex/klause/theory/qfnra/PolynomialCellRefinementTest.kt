package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialCellRefinementTest {
    @Test
    fun `an equality excludes the open interval between its irrational roots`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)

        val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
            algebraic.rational(BigFraction.ZERO), arithmetic, algebraic))

        assertFalse(cell.lowerClosed)
        assertFalse(cell.upperClosed)
        assertTrue(cell.contains(algebraic.rational(BigFraction.ONE), algebraic))
        assertFalse(cell.contains(assertNotNull(cell.lower), algebraic))
        assertFalse(cell.contains(assertNotNull(cell.upper), algebraic))
        assertIs<PolynomialCellScope.Source>(cell.scope)
    }

    @Test
    fun `strict and disequal repeated roots exclude their closed section`() {
        for (predicate in listOf("(> (* x x) 0)", "(distinct (* x x) 0)")) {
            val source = SmtLibPolynomial.parse("(declare-const x Real) (assert $predicate)")
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)

            val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
                algebraic.rational(BigFraction.ZERO), arithmetic, algebraic))

            assertTrue(cell.lowerClosed)
            assertTrue(cell.upperClosed)
            assertTrue(cell.contains(algebraic.rational(BigFraction.ZERO), algebraic))
            assertFalse(cell.contains(algebraic.rational(BigFraction.ONE), algebraic))
        }
    }

    @Test
    fun `an unrelated predicate cannot acquire mandatory source support`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0))")
        val other = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val predicate = PolynomialFormula.normalize(other.formula, arithmetic).atoms.single()

        val cell = CheckedPolynomialCell.rejected(predicate, scope, algebraic.rational(BigFraction.ZERO),
            arithmetic, algebraic)

        assertNull(cell)
    }

    @Test
    fun `a variable leading coefficient does not yield a unary cell`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (= (* x y) 1))",
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)

        val cell = CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
            algebraic.rational(BigFraction.ZERO), arithmetic, algebraic)

        assertNull(cell)
    }

    @Test
    fun `the seed repairs an irrational equality and its strict sign together`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (and (= (* x x) 2) (> x 0)))")
        val query = QfNraQuery(source.formula, 0, 1, QfNraParams(cellRefinement = true), PolynomialCondition())

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        val witness = assertIs<QfNraResult.Satisfiable>(result).assignment.realValue(0)
        val algebraic = AlgebraicArithmetic()
        assertTrue(algebraic.sign(witness) > 0)
        assertEquals(0, algebraic.signAt(PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            .atoms.first().polynomial, mapOf(0 to witness)))
        assertTrue(query.checkedCells.isNotEmpty())
        assertTrue(query.checkedCells.size <= 8)
        assertTrue(assertNotNull(query.cellStats).work <= 16_384)
        assertEquals(0L, result.stats.projectedPolynomials)
    }

    @Test
    fun `conditional predicates retain the exact requested fixing`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const n Real) (declare-const x Real) (assert (= (* n n x x) 8))",
        )
        val condition = PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(2)),
            upper = mapOf(0 to BigFraction.ofLong(2)))
        val query = QfNraQuery(source.formula, 0, 2, QfNraParams(cellRefinement = true), condition)

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        assertIs<QfNraResult.Satisfiable>(result)
        val scope = assertIs<PolynomialCellScope.Query>(query.checkedCells.first().scope)
        assertEquals(condition, scope.condition)
    }

    @Test
    fun `an enclosing stop interrupts cell production without a certificate`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        var cancelled = false
        val work = QfNraWork(QfNraParams(Cancellation { cancelled }))
        val refinement = PolynomialCellRefinement(source.formula, PolynomialCondition(), work,
            PolynomialLimits()) {}
        val normalized = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        cancelled = true

        assertFailsWith<PolynomialInterrupted> {
            refinement.prepare(normalized)
        }
        assertTrue(refinement.cells.isEmpty())
    }
    @Test
    fun `an optional degree decline preserves complete fallback`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (^ x 17) 1))")
        val query = QfNraQuery(source.formula, 0, 1, QfNraParams(cellRefinement = true), PolynomialCondition())

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        assertIs<QfNraResult.Satisfiable>(result)
        assertTrue(query.checkedCells.isEmpty())
    }

    @Test
    fun `conditional Boolean premises cannot acquire source global support`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const gate Bool) (assert (or gate (= (* x x) 2)))",
        )
        val condition = PolynomialCondition(bools = mapOf(0 to false))
        val query = QfNraQuery(source.formula, 1, 1, QfNraParams(cellRefinement = true), condition)

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        assertIs<QfNraResult.Satisfiable>(result)
        val scope = assertIs<PolynomialCellScope.Query>(query.checkedCells.first().scope)
        assertEquals(mapOf(0 to false), scope.condition.bools)
    }

    @Test
    fun `zero containing work prevents the optional producer from entering`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val query = QfNraQuery(source.formula, 0, 1,
            QfNraParams(maximumWork = 0, cellRefinement = true), PolynomialCondition())

        val result = query.next()

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(0L, result.stats.workSpent)
        assertTrue(query.checkedCells.isEmpty())
    }
    @Test
    fun `cancellation after a completed cell preserves only that completed certificate`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        var stopped = false
        val query = QfNraQuery(source.formula, 0, 1,
            QfNraParams(Cancellation { stopped }, cellRefinement = true), PolynomialCondition())
        query.checkedCellObserver = { stopped = true }

        var result: QfNraResult? = null
        while (result == null) result = query.next()

        assertEquals(QfNraReason.Interrupted, assertIs<QfNraResult.Unknown>(result).reason)
        assertEquals(1, query.checkedCells.size)
        assertIs<PolynomialCellScope.Source>(query.checkedCells.single().scope)
    }
}
