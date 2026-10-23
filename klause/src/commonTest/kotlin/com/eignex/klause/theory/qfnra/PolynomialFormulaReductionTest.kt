package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolynomialFormulaReductionTest {
    @Test
    fun `committed snapshots retain their own reconstruction fibres`() {
        for (length in listOf(1, 2, 4)) {
            val declarations = (0..length).joinToString(" ") { "(declare-const x$it Real)" }
            val equations = (0 until length).joinToString(" ") { "(= x$it (+ x${it + 1} 1))" }
            val source = SmtLibPolynomial.parse("$declarations (assert (and $equations))")
            val arithmetic = PolynomialArithmetic()
            val algebraic = AlgebraicArithmetic(arithmetic)
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val snapshots = PolynomialFormulaReduction.stages(formula, source.realCount, arithmetic)
                .asSequence().filterNotNull().toList()

            assertEquals(0, snapshots.first().eliminatedCount)
            assertFalse(snapshots.first().finished)
            assertTrue(snapshots.last().finished)
            assertEquals(length, snapshots.last().eliminatedCount)
            for (snapshot in snapshots.drop(1)) {
                val values = (0 until source.realCount).map { id ->
                    val active = snapshot.formula.atoms.any { id in it.polynomial.variables }
                    algebraic.rational(BigFraction.ofLong(if (active) (length - id).toLong() else 0))
                }
                val point = snapshot.reconstruct(QfNraAssignment(booleanArrayOf(), values), arithmetic, algebraic)
                for (id in 0 until length) {
                    assertEquals(BigFraction.ONE,
                        point.getValue(id).rational!! - point.getValue(id + 1).rational!!)
                }
            }
        }
    }

    @Test
    fun `an interrupted rewrite leaves its last committed snapshot intact`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= x 0) (> y x)))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val preparing = PolynomialFormulaReduction.stages(formula, source.realCount, arithmetic)
        val initial = requireNotNull(preparing.next())
        assertNull(preparing.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { preparing.next() }
        assertEquals(0, initial.eliminatedCount)
        assertEquals(formula.atoms, initial.formula.atoms)
        assertFalse(initial.inconsistent)
    }

    @Test
    fun `a reduced probe reconstructs every original coordinate before publication`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (= x (+ (* y y) 1)) (= y 2) (= b (> x 4))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000))

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(2, point.realCount)
        assertEquals(BigFraction.ofLong(5), point.realValue(0).rational)
        assertEquals(BigFraction.ofLong(2), point.realValue(1).rational)
        assertTrue(point.boolValue(0))
    }

    @Test
    fun `a reified required graph rewrites predicates outside the required conjunction`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(declare-const b Bool) (declare-const c Bool) " +
                "(assert (and b (= b (= x (* y y))) (or c (> x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(reduction.inconsistent)
        assertEquals(1, reduction.eliminatedCount)
        assertTrue(reduction.formula.atoms.all { 0 !in it.polynomial.variables })
        assertTrue(reduction.formula.atoms.any { 1 in it.polynomial.variables })
    }

    @Test
    fun `an existential inequality retains its coordinate in optional predicates`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and (> x 0) (or b (< x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(reduction.inconsistent)
        assertEquals(0, reduction.eliminatedCount)
        assertEquals(formula.atoms, reduction.formula.atoms)
    }

    @Test
    fun `a negated disequality supplies a total graph with its required polarity`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (not (distinct x (* y y))) (or b (> x 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(reduction.inconsistent)
        assertEquals(1, reduction.eliminatedCount)
        assertTrue(reduction.formula.atoms.all { 0 !in it.polynomial.variables })
    }

    @Test
    fun `total graph expansion can exceed the input term footprint`() {
        for (prefix in listOf(false, true)) {
            val fixed = if (prefix) "(= p 0)" else ""
            val source = SmtLibPolynomial.parse(
                "(declare-const p Real) (declare-const x Real) (declare-const y Real) " +
                    "(declare-const z Real) (declare-const b Bool) " +
                    "(assert (and $fixed (= x (+ (* y y) (* z z))) (or b (> (^ x 20) 0))))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
                .asSequence().filterNotNull().single()

            assertFalse(reduction.inconsistent)
            assertEquals(if (prefix) 2 else 1, reduction.eliminatedCount)
            assertTrue(reduction.formula.atoms.none { 1 in it.polynomial.variables })
        }
    }

    @Test
    fun `total graph coefficients can exceed the input scalar footprint`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and (= x (/ 1234567891 1234567)) (or b (> (^ x 20) 0))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()

        assertFalse(reduction.inconsistent)
        assertEquals(1, reduction.eliminatedCount)
        assertTrue(reduction.formula.atoms.none { 0 in it.polynomial.variables })
    }

    @Test
    fun `a globally forced total graph retains both Boolean alternatives`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (= x (* y y)) (or (and b (> y 0)) (and (not b) (< y 0)))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()
        val regions = reduction.formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertFalse(reduction.inconsistent)
        assertEquals(setOf(false, true), regions.map { it.bools.getValue(0) }.toSet())
    }

    @Test
    fun `an unforced guarded graph retains an off graph witness`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) " +
                "(assert (or (and b (= x0 (* x1 x1)) (> x1 0)) " +
                "(and (not b) (= x0 1) (= x1 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000))

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(false, point.boolValue(0))
        assertEquals(BigFraction.ONE, point.realValue(0).rational)
        assertEquals(BigFraction.ZERO, point.realValue(1).rational)
    }

    @Test
    fun `product equalities retain either singular coordinate fibre`() {
        for ((zero, positive) in listOf(0 to 1, 1 to 0)) {
            val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
            val source = SmtLibPolynomial.parse(
                "$declarations (assert (and (= (* x0 x1) 0) (= x$zero 0) (> x$positive 2)))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000))

            val point = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(BigFraction.ZERO, point.realValue(zero).rational)
            assertTrue(requireNotNull(point.realValue(positive).rational) > BigFraction.ofLong(2))
        }
    }

    @Test
    fun `mandatory opposite weak bounds retain their exact graph equality`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) " +
                "(assert (and (>= (- x0 (* x1 x1)) 0) (<= (- x0 (* x1 x1)) 0) " +
                "(< x0 0) (or b (not b))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `global coordinate constants retain the required source Boolean value`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) " +
                "(assert (and (= x0 0) (= (* x1 x1) 0) (or b (> x0 0))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000))

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(point.boolValue(0))
        assertEquals(BigFraction.ZERO, point.realValue(0).rational)
    }

    @Test
    fun `dependent graph fibres reconstruct exact algebraic coordinates`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (assert (and (= x0 (+ (* x1 x1) 1)) (= x2 (* x0 x0)) " +
                "(= (* x1 x1) 2) (> x1 0)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000))

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertEquals(BigFraction.ofLong(3), point.realValue(0).rational)
        assertEquals(BigFraction.ofLong(9), point.realValue(2).rational)
        assertNull(point.realValue(1).rational)
    }

    @Test
    fun `conditioned queries retain separate graph fibres`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) " +
                "(assert (or (and b (= x0 (* x1 x1)) (= x1 2)) (and (not b) (= x0 7))))",
        )
        for ((truth, expected) in listOf(true to 4L, false to 7L)) {
            val condition = PolynomialCondition(bools = mapOf(0 to truth))

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000), condition)

            val point = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(BigFraction.ofLong(expected), point.realValue(0).rational)
        }
    }

    @Test
    fun `representation refusal retains only completed total substitutions`() {
        for (prefix in listOf(false, true)) {
            val fixed = if (prefix) "(= x 0)" else ""
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (declare-const z Real) " +
                    "(assert (and $fixed (= y (* z z)) (> (^ y 4) 0)))",
            )
            val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
            val arithmetic = PolynomialArithmetic(limits = PolynomialLimits(maxDenseCoefficients = 3))

            val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
                .asSequence().filterNotNull().single()

            assertFalse(reduction.inconsistent)
            assertEquals(if (prefix) 1 else 0, reduction.eliminatedCount)
            assertTrue(reduction.formula.atoms.any { 1 in it.polynomial.variables })
            if (prefix) assertTrue(reduction.formula.atoms.none { 0 in it.polynomial.variables })
        }
    }

    @Test
    fun `containing cancellation withholds a partially reduced formula`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (assert (and (= x 0) (> y x)))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var stopped = false
        val arithmetic = PolynomialArithmetic(Cancellation { stopped })
        val preparing = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
        assertNull(preparing.next())

        stopped = true

        assertFailsWith<PolynomialInterrupted> { preparing.next() }
    }

    @Test
    fun `containing work exhaustion withholds a completed global refutation`() {
        val declarations = (0..32).joinToString(" ") { "(declare-const x$it Real)" }
        val source = SmtLibPolynomial.parse(
            "$declarations (declare-const b Bool) (assert (and (= x0 0) (or b (> x0 0)) (not b)))",
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, source.boolCount,
            source.realCount, QfNraParams(maximumWork = 500_000)))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
