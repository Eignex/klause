package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialLpPruningTest {
    @Test
    fun `a spatial refutation should retire both closed children`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const y Real)
            (assert (and (>= x 0) (<= x 1) (>= y 0) (<= y 1)
                (= (+ x y) 1) (>= (* x y) (/ 3 10))))
        """.trimIndent())
        val work = QfNraWork(QfNraParams(maximumWork = 100_000))
        val arithmetic = PolynomialArithmetic(work).also { work.execution = it.execution }
        val atoms = assertNotNull(PolynomialFormula.normalize(source.formula, arithmetic).forcedAtoms(arithmetic))

        val result = PolynomialLpPruning(atoms, 2, arithmetic, work).use { pruning ->
            var step = pruning.advance { null }
            while (step == PolynomialLpStep.Pending) step = pruning.advance { null }
            step
        }

        assertIs<PolynomialLpStep.Refuted>(result)
        assertTrue(work.envelopeRefutations > 1)
    }

    @Test
    fun `certified square bounds should refute a product beyond an even power sum`() {
        val premises = listOf(
            "(< (+ (* x x) (* y y)) 1)",
            "(>= (+ (* (- 2) x x) (* (- 2) y y)) (- 2))",
            "(= (+ (* (- 2) x x) (* (- 2) y y)) (- 2))",
            "(<= (+ (* (/ 1 2) x x) (* (/ 1 2) y y)) (/ 1 2))",
        )
        for (premise in premises) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert $premise) (assert (> (* x y) 1))")
            val work = QfNraWork(QfNraParams(maximumWork = 100_000))
            val arithmetic = PolynomialArithmetic(work).also { work.execution = it.execution }
            val formula = PolynomialFormula.normalize(source.formula, arithmetic)
            val atoms = assertNotNull(formula.forcedAtoms(arithmetic))

            val result = PolynomialLpPruning(atoms, source.realCount, arithmetic, work).use { pruning ->
                var step = pruning.advance { null }
                while (step == PolynomialLpStep.Pending) step = pruning.advance { null }
                step
            }

            assertIs<PolynomialLpStep.Refuted>(result, premise)
            assertTrue(work.envelopeQueries > 0)
        }
    }

    @Test
    fun `closed square endpoints should retain equality on either side`() {
        for (boundary in listOf("(>= x 1)", "(<= x (- 1))")) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (assert (<= (* x x) 1)) (assert $boundary)")

            val result = solveQfNra(source.formula, 0, 1)

            assertIs<QfNraResult.Satisfiable>(result, boundary)
        }
    }

    @Test
    fun `an unbounded product operand should retain feasible source points`() {
        for (premise in listOf(
            "(<= (+ (* x x) y) 1)", "(<= (- (* x x) (* y y)) 1)", "(<= (* x x) 1)",
        )) {
            val source = SmtLibPolynomial.parse(
                "(declare-const x Real) (declare-const y Real) (assert $premise) (assert (> (* x y) 1))")

            val result = solveQfNra(source.formula, 0, 2)

            assertIs<QfNraResult.Satisfiable>(result, premise)
        }
    }

    @Test
    fun `a refuted envelope region should retain the other Boolean alternative`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real) (declare-const y Real)
            (assert (or (< (+ (* x x) (* y y)) 1) (and (= x 2) (= y 2))))
            (assert (> (* x y) 1))
        """.trimIndent())

        val result = solveQfNra(source.formula, 0, 2)

        assertIs<QfNraResult.Satisfiable>(result)
    }
}
