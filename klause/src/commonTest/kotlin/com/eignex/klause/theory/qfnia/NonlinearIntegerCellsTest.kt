package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.CheckedPolynomialCell
import com.eignex.klause.theory.qfnra.PolynomialBooleanRegion
import com.eignex.klause.theory.qfnra.PolynomialCellScope
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialFormula
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NonlinearIntegerCellsTest {
    @Test
    fun `integer hulls should preserve exact signed boundaries and closure`() {
        val cases = listOf(
            Triple("(= (* n n) 2)", 0L, listOf(-2 to false, -1 to true, 0 to true, 1 to true, 2 to false)),
            Triple("(distinct n 3)", 3L, listOf(2 to false, 3 to true, 4 to false)),
            Triple("(> n (/ 1 2))", 0L, listOf(-1 to true, 0 to true, 1 to false)),
            Triple("(< n (/ (- 1) 2))", 0L, listOf(-1 to false, 0 to true, 1 to true)),
            Triple("(< n 3)", 4L, listOf(3 to false, 4 to true)),
            Triple("(> n (- 3))", -4L, listOf(-4 to true, -3 to false)),
        )
        for ((predicate, rejected, candidates) in cases) {
            val source = SmtLibPolynomial.parse("(declare-const n Real) (assert $predicate)")
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
            val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
                algebraic.rational(BigFraction.ofLong(rejected)), arithmetic, algebraic))
            val cells = NonlinearIntegerCells(source.formula, listOf(0), arithmetic)
            cells.retain(cell)

            for ((value, expected) in candidates) {
                val excluded = cells.excludes(listOf(bigIntOf(value.toLong())), algebraic)

                assertEquals(expected, excluded, "$predicate at $value")
            }
        }
    }

    @Test
    fun `wide irrational hulls should preserve integer neighbors beyond machine words`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const n Real) (assert (= (* (- n 100000000000000000000) " +
                "(- n 100000000000000000000)) 2))",
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val center = parseBigInt("100000000000000000000")
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
            algebraic.rational(BigFraction.of(center, BIG_ONE)), arithmetic, algebraic))
        val cells = NonlinearIntegerCells(source.formula, listOf(0), arithmetic)
        cells.retain(cell)

        for ((offset, expected) in listOf(-2 to false, -1 to true, 0 to true, 1 to true, 2 to false)) {
            val excluded = cells.excludes(listOf(center + bigIntOf(offset.toLong())), algebraic)

            assertEquals(expected, excluded)
        }
    }

    @Test
    fun `query and regional certificates should not enter integer source stores`() {
        val source = SmtLibPolynomial.parse("(declare-const n Real) (assert (= (* n n) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val snapshot = PolynomialFormula.normalize(source.formula, arithmetic)
        val condition = PolynomialCondition()
        val scopes = listOf(
            PolynomialCellScope.Query(source.formula, condition, snapshot),
            PolynomialCellScope.Region(source.formula, condition, snapshot,
                PolynomialBooleanRegion(mapOf(0 to true), emptyMap()), Any()),
        )
        for (scope in scopes) {
            val cell = assertNotNull(CheckedPolynomialCell.rejected(snapshot.atoms.single(), scope,
                algebraic.rational(BigFraction.ZERO), arithmetic, algebraic))
            val cells = NonlinearIntegerCells(source.formula, listOf(0), arithmetic)

            cells.retain(cell)

            assertFalse(cells.excludes(listOf(bigIntOf(0)), algebraic))
            assertEquals(1L, cells.stats.declined)
        }
    }

    @Test
    fun `source certificates should require the owning formula identity`() {
        val source = SmtLibPolynomial.parse("(declare-const n Real) (assert (= (* n n) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
            algebraic.rational(BigFraction.ZERO), arithmetic, algebraic))
        val other = PolynomialRealFormula.And(listOf(source.formula))
        val cells = NonlinearIntegerCells(other, listOf(0), arithmetic)

        cells.retain(cell)

        assertFalse(cells.excludes(listOf(bigIntOf(0)), algebraic))
        assertEquals(1L, cells.stats.declined)
    }

    @Test
    fun `real coordinate cells should not acquire an unrelated integer identity`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const r Real) (declare-const n Real) (assert (= (* r r) 2))",
        )
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
            algebraic.rational(BigFraction.ZERO), arithmetic, algebraic))
        val cells = NonlinearIntegerCells(source.formula, listOf(1), arithmetic)

        cells.retain(cell)

        assertFalse(cells.excludes(listOf(bigIntOf(0)), algebraic))
        assertEquals(1L, cells.stats.declined)
    }

    @Test
    fun `retiring source cells should remove their exclusion authority`() {
        val source = SmtLibPolynomial.parse("(declare-const n Real) (assert (= (* n n) 2))")
        val arithmetic = PolynomialArithmetic()
        val algebraic = AlgebraicArithmetic(arithmetic)
        val scope = PolynomialCellScope.Source.prepare(source.formula, arithmetic)
        val cell = assertNotNull(CheckedPolynomialCell.rejected(scope.predicates.single(), scope,
            algebraic.rational(BigFraction.ZERO), arithmetic, algebraic))
        val cells = NonlinearIntegerCells(source.formula, listOf(0), arithmetic)
        cells.retain(cell)
        assertTrue(cells.excludes(listOf(bigIntOf(0)), algebraic))

        cells.clear()

        assertFalse(cells.excludes(listOf(bigIntOf(0)), algebraic))
    }
}
