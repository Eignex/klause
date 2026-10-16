package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolynomialMagnitudeRefutationTest {
    @Test
    fun `positive even sums bound a product without search`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (< (+ (* x x) (* y y)) 1)) (assert (> (* x y) 1))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096)

        assertTrue(refuted)
        assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 0, 2))
    }

    @Test
    fun `non-strict source boundaries retain a feasible equality`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (<= (* x x) 1)) (assert (>= x 1))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096)

        assertFalse(refuted)
        assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 1))
    }

    @Test
    fun `oriented comparisons retain rational coefficients and equality consequences`() {
        for (premise in listOf(
            "(>= (+ (* (- 2) x x) (* (- 2) y y)) (- 2))",
            "(= (+ (* (- 2) x x) (* (- 2) y y)) (- 2))",
            "(<= (+ (* (/ 1 2) x x) (* (/ 1 2) y y)) (/ 1 2))",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert $premise) (assert (> (* x y) 1))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096)

            assertTrue(refuted, premise)
        }
    }

    @Test
    fun `zero upper bound forces each nonnegative summand to zero`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (<= (+ (* x x) (* y y y y)) 0)) (assert (distinct (* x y) 0))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096)

        assertTrue(refuted)
    }

    @Test
    fun `unsupported signs and missing coordinate bounds supply no refutation`() {
        for (premise in listOf(
            "(<= (+ (* x x) y) 1)", "(<= (- (* x x) (* y y)) 1)", "(<= (* x x) 1)",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert $premise) (assert (> (* x y) 1))",
            )
            val arithmetic = PolynomialArithmetic()
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)

            val refuted = refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096)

            assertFalse(refuted, premise)
        }
    }

    @Test
    fun `guarded magnitude bounds cannot refute another source alternative`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (or (< (+ (* x x) (* y y)) 1) (and (= x 2) (= y 2))))
            (assert (> (* x y) 1))
            """.trimIndent(),
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)

        val refuted = refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096)

        assertFalse(refuted)
        assertIs<QfNraResult.Satisfiable>(solveQfNra(source.formula, 0, 2))
    }

    @Test
    fun `admission declines withhold a refutation`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (<= (* (/ 1 3) x x) 3)) (assert (> (* (/ 1 3) x) 3))",
        )
        val arithmetic = PolynomialArithmetic()
        val formula = PolynomialFormula.normalize(source.formula, arithmetic)
        assertTrue(refutePolynomialMagnitudes(formula, arithmetic, 128, 2048, 8, 4096))

        for ((terms, degree, bits) in listOf(Triple(1, 8, 4096), Triple(2048, 1, 4096), Triple(2048, 8, 2))) {
            assertFalse(refutePolynomialMagnitudes(formula, arithmetic, 128, terms, degree, bits))
        }
    }

    @Test
    fun `interruption cannot publish a partial magnitude refutation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (< (+ (* x x) (* y y)) 1)) (assert (> (* x y) 1))
            """.trimIndent(),
        )
        val formula = PolynomialFormula.normalize(source.formula, PolynomialArithmetic())
        var completedWork = 0L
        val counter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { completedWork += it }
        }
        assertTrue(refutePolynomialMagnitudes(formula, PolynomialArithmetic(counter), 128, 2048, 8, 4096))

        for (limit in listOf(1L, completedWork / 2, completedWork - 1)) {
            var spent = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= limit
                override fun workMeter(): WorkMeter = WorkMeter { spent += it }
            }
            assertFailsWith<PolynomialInterrupted> {
                refutePolynomialMagnitudes(formula, PolynomialArithmetic(stop), 128, 2048, 8, 4096)
            }
        }
    }

    @Test
    fun `containing work exhaustion withholds the magnitude refutation`() {
        val source = SmtLibPolynomial.parse(
            """
            (declare-const x Real) (declare-const y Real)
            (assert (< (+ (* x x) (* y y)) 1)) (assert (> (* x y) 1))
            """.trimIndent(),
        )
        val completed = assertIs<QfNraResult.Unsatisfiable>(solveQfNra(source.formula, 0, 2))

        val stopped = solveQfNra(source.formula, 0, 2,
            QfNraParams(maximumWork = completed.stats.workSpent - 1))

        assertEquals(QfNraReason.WorkLimit, assertIs<QfNraResult.Unknown>(stopped).reason)
    }

}
