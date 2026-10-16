package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolynomialBooleanRefutationTest {
    @Test
    fun `all contradictory Boolean branches refute before polynomial projection`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool) (declare-const x Real)
            (assert (and (or a b) (or a (not b)) (or (not a) b) (or (not a) (not b))
              (or (= (* x x) 2) (= (* x x) 3))))
            """.trimIndent(),
        )

        val result = assertIs<QfNraResult.Unsatisfiable>(
            solveQfNra(source.formula, source.boolCount, source.realCount),
        )

        assertEquals(0L, result.stats.projectedPolynomials)
        assertEquals(0L, result.stats.liftedCells)
    }

    @Test
    fun `a feasible later branch survives rollback of gate implications`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (assert (and (or a b) (or a (not b)) (or (not a) b)))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        assertFalse(formula.refuteBooleans(arithmetic, 128, 2048, 4096))
        val point = assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 2, 0)).assignment
        assertTrue(point.boolValue(0))
        assertTrue(point.boolValue(1))
    }

    @Test
    fun `compound negations preserve shared Boolean correlation`() {
        for (assertion in listOf(
            "(and (not (and a b)) (not (or (not a) (not b))))",
            "(and (or a b) (not (or a b)))",
            "(and (not (or a b)) (or a b))",
            "(and (not (not a)) (not a))",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const a Bool) (declare-const b Bool) (assert $assertion)",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            assertTrue(formula.refuteBooleans(arithmetic, 128, 2048, 4096), assertion)
        }
    }

    @Test
    fun `guarded numeric alternatives remain separate obligations`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const x Real)
            (assert (and (=> a (= (* x x) 4)) (=> (not a) (= (* x x) 9))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        assertFalse(formula.refuteBooleans(arithmetic, 128, 2048, 4096))
        assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 1, 1))
    }

    @Test
    fun `numeric predicates supply no truth to the Boolean relaxation`() {
        for (relation in listOf("<", "=", ">")) {
            val source = SmtLibPolynomial.parse(
                "(declare-const a Bool) (declare-const x Real) (assert (and a ($relation (* x x) 0)))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            assertFalse(formula.refuteBooleans(arithmetic, 128, 2048, 4096), relation)
        }
    }

    @Test
    fun `repeated numeric predicates retain their Boolean identity`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const x Real)
            (assert (and (or a (= (* x x) 2)) (or a (not (= (* x x) 2)))
              (or (not a) (= (* x x) 2)) (or (not a) (not (= (* x x) 2)))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        assertTrue(formula.refuteBooleans(arithmetic, 128, 2048, 4096))
    }

    @Test
    fun `constants and empty gates retain their Boolean truth`() {
        val variable = PolynomialRealFormula.BooleanVariable(SourceBoolId(0))
        for ((operand, expected) in listOf(
            PolynomialRealFormula.Constant(false) to true,
            PolynomialRealFormula.Constant(true) to false,
            PolynomialRealFormula.And(emptyList()) to false,
            PolynomialRealFormula.Or(emptyList()) to true,
        )) {
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(PolynomialRealFormula.And(listOf(variable, operand)), arithmetic)

            assertEquals(expected, formula.refuteBooleans(arithmetic, 128, 2048, 4096))
        }
    }

    @Test
    fun `full input admission precedes a Boolean refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const a Bool) (declare-const x Real) (assert (and a (not a) (> (* x x) 4)))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        assertTrue(formula.refuteBooleans(arithmetic, 128, 2048, 4096))

        for ((atoms, terms, bits) in listOf(Triple(0, 2048, 4096), Triple(128, 1, 4096), Triple(128, 2048, 2))) {
            assertFalse(formula.refuteBooleans(arithmetic, atoms, terms, bits))
        }
    }

    @Test
    fun `gate references share the bounded representation admission`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (assert (and (or a b) (or a (not b)) (or (not a) b) (or (not a) (not b))))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        assertFalse(formula.refuteBooleans(arithmetic, 128, 10, 4096))
        assertTrue(formula.refuteBooleans(arithmetic, 128, 2048, 4096))
    }

    @Test
    fun `pure numeric sources spend no work on source Boolean refutation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        val interrupted = PolynomialArithmetic(Cancellation { true })

        assertFalse(formula.refuteBooleans(interrupted, 128, 2048, 4096))
    }

    @Test
    fun `Boolean pins remain confined to their conditioned query`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (assert (or (and a b) (and (not a) (not b))))
            """.trimIndent(),
        )

        assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 2, 0,
            condition = PolynomialCondition(bools = mapOf(0 to true, 1 to false))))
        assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 2, 0))
    }

    @Test
    fun `interruption withholds incomplete Boolean branch exhaustion`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool)
            (assert (and (or a b) (or a (not b)) (or (not a) b) (or (not a) (not b))))
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        assertTrue(formula.refuteBooleans(PolynomialArithmetic(counter), 128, 2048, 4096))

        for (limit in listOf(1L, completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                formula.refuteBooleans(PolynomialArithmetic(stop), 128, 2048, 4096)
            }
        }
    }

    @Test
    fun `containing work exhaustion withholds a completed Boolean refutation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const a Bool) (declare-const b Bool) (declare-const x Real)
            (assert (and (or a b) (or a (not b)) (or (not a) b) (or (not a) (not b))
              (or (= (* x x) 2) (= (* x x) 3))))
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 2, 1))

        val stopped = solveQfNra(source.formula, 2, 1,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }
}
