package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.util.bigIntOf
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class SmtLibAffineObjectiveAdmissionTest(private val direction: String, private val zeroSquare: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} zeroSquare={1}")
        fun variants(): List<Array<Any>> = listOf("minimize", "maximize").flatMap { direction ->
            listOf(false, true).map { arrayOf<Any>(direction, it) }
        }
    }

    @Test
    fun `mixed polynomial objective admission preserves exact source coefficients and direction`() {
        val term = if (zeroSquare) "(+ (/ y 10) (* 0 x x))" else "(/ y 10)"
        val text = """
            (declare-const i Int) (declare-const x Real) (declare-const y Real)
            (assert (and (>= i 0) (<= i 1))) (assert (= x 1))
            (assert (= (* (to_real i) x) y))
            ($direction (+ (/ 7 3) $term)) (check-sat)
        """.trimIndent()

        val captured = assertIs<SmtLibInput.Quadratic>(SmtLibAdmission.parse(text))

        assertIs<SmtLibInput.NonlinearInteger>(captured.source)
        assertEquals(if (direction == "minimize") ObjectiveSense.MINIMIZE else ObjectiveSense.MAXIMIZE,
            captured.sense)
        assertEquals(QuadraticCoefficient.of(bigIntOf(7), bigIntOf(3)), captured.objective.constant)
        assertEquals(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.of(bigIntOf(1), bigIntOf(10)),
            QuadraticVariable.Real(SourceRealId(1)))), captured.objective.terms)
    }

    @Test
    fun `representable mixed polynomial objectives share original source admission`() {
        val term = if (zeroSquare) "(+ y (* 0 x x))" else "y"
        val text = """
            (declare-const i Int) (declare-const x Real) (declare-const y Real)
            (assert (and (>= i 0) (<= i 1))) (assert (= x 1))
            (assert (= (* (to_real i) x) y))
            ($direction (+ 7 $term)) (check-sat)
        """.trimIndent()

        val captured = assertIs<SmtLibInput.Quadratic>(SmtLibAdmission.parse(text))

        assertIs<SmtLibInput.NonlinearInteger>(captured.source)
        assertEquals(if (direction == "minimize") ObjectiveSense.MINIMIZE else ObjectiveSense.MAXIMIZE,
            captured.sense)
        assertEquals(QuadraticCoefficient.ofLong(7), captured.objective.constant)
        assertEquals(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE,
            QuadraticVariable.Real(SourceRealId(1)))), captured.objective.terms)
    }
}
