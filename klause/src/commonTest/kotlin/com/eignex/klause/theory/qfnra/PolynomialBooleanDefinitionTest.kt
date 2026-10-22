package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
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

class PolynomialBooleanDefinitionTest {
    @Test
    fun `numeric regions omit definitions outside the supporting source implicant`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) " +
                "(declare-const b Bool) (declare-const c Bool) " +
                "(assert (and (= b (> (^ x 10) 0)) (= c (> y 0)) (or (not b) (not c))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduced = assertNotNull(formula.reduceBooleanDefinitions(arithmetic))
        val regions = reduced.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertEquals(2, regions.size)
        assertTrue(regions.all { it.atoms.size == 1 && it.bools.isEmpty() })
        assertEquals(setOf(0, 1), regions.flatMap { it.atoms.keys }.toSet())
    }

    @Test
    fun `both witness paths recover original Boolean values without real fibres`() {
        for (realCount in listOf(1, 33)) {
            val declarations = (0 until realCount).joinToString(" ") { "(declare-const x$it Real)" }
            val source = SmtLibPolynomial.parse(
                "$declarations (declare-const b Bool) (assert (= b (> x0 0)))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
                .asSequence().filterNotNull().single()
            val condition = PolynomialCondition(lower = mapOf(0 to BigFraction.ONE),
                upper = mapOf(0 to BigFraction.ofLong(2)))

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000), condition)

            assertEquals(0, reduction.eliminatedCount)
            assertTrue(reduction.requiresOriginalCheck)
            val point = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(realCount, point.realCount)
            assertTrue(point.boolValue(0))
        }
    }

    @Test
    fun `conditioned Boolean pins constrain their predicates and retract between queries`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (= b (> x 0)))",
        )
        val invocation = PolynomialTheoryService(source.formula, source.boolCount, source.realCount).invocation()

        try {
            for (truth in listOf(true, false, true)) {
                val condition = PolynomialCondition(bools = mapOf(0 to truth),
                    lower = mapOf(0 to BigFraction.ONE), upper = mapOf(0 to BigFraction.ofLong(2)))

                val result = invocation.check(condition)

                if (truth) assertTrue(assertIs<QfNraResult.Satisfiable>(result).assignment.boolValue(0))
                else assertIs<QfNraResult.Unsatisfiable>(result)
            }
        } finally {
            invocation.close()
        }
    }

    @Test
    fun `negated predicate definitions reconstruct the opposite Boolean truth`() {
        for ((value, truth) in listOf(1L to false, -1L to true)) {
            val numeral = if (value < 0) "(- 1)" else "1"
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const b Bool) " +
                    "(assert (and (= b (not (> x 0))) (= x $numeral)))",
            )

            val result = solveQfNra(source.formula, source.boolCount, source.realCount,
                QfNraParams(maximumWork = 500_000))

            val point = assertIs<QfNraResult.Satisfiable>(result).assignment
            assertEquals(BigFraction.ofLong(value), point.realValue(0).rational)
            assertEquals(truth, point.boolValue(0))
        }
    }

    @Test
    fun `an optional guarded definition retains an off definition witness`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (declare-const c Bool) " +
                "(assert (or (and c (= b (> x 0))) (and (not c) b (= x (- 1)))))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000), PolynomialCondition(bools = mapOf(1 to false)))

        val point = assertIs<QfNraResult.Satisfiable>(result).assignment
        assertTrue(point.boolValue(0))
        assertFalse(point.boolValue(1))
        assertEquals(BigFraction.ofLong(-1), point.realValue(0).rational)
    }

    @Test
    fun `multiple definitions retain the required correlation between numeric predicates`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const y Real) (declare-const b Bool) " +
                "(assert (and (= b (> x 0)) (= b (< y 0)) (= x 1) (= y 1)))",
        )

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = 500_000))

        assertIs<QfNraResult.Unsatisfiable>(result)
    }

    @Test
    fun `alternative conjunctions define the same total Boolean predicate`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (or (and b (> x 0)) (and (not b) (not (> x 0)))))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val reduction = PolynomialFormulaReduction.prepare(formula, source.realCount, arithmetic)
            .asSequence().filterNotNull().single()
        val regions = reduction.formula.booleanRegions(arithmetic).asSequence().filterNotNull().toList()

        assertFalse(reduction.inconsistent)
        assertTrue(reduction.requiresOriginalCheck)
        assertEquals(1, regions.size)
        assertTrue(regions.single().atoms.isEmpty())
        assertTrue(regions.single().bools.isEmpty())
    }

    @Test
    fun `containing cancellation withholds a partially substituted Boolean formula`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (= b (> x 0)))",
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val arithmetic = PolynomialArithmetic(Cancellation { true })

        assertFailsWith<PolynomialInterrupted> { formula.reduceBooleanDefinitions(arithmetic) }
    }

    @Test
    fun `containing work exhaustion withholds a reconstructed Boolean witness`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (declare-const b Bool) " +
                "(assert (and (= b (> x 0)) (= x 1)))",
        )
        val completed = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, source.boolCount,
            source.realCount, QfNraParams(maximumWork = 500_000)))

        val result = solveQfNra(source.formula, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(result).reason)
    }
}
