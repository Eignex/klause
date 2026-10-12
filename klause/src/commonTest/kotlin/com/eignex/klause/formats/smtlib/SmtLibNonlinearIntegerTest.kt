package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceRealId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibNonlinearIntegerTest {
    @Test
    fun `mixed declaration identities distinguish integer and real coordinates`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (declare-const r Real) (declare-const y Int) " +
                "(assert (= (* (to_real x) r (to_real y)) 6))",
            allowIntegers = true,
        )

        assertEquals(mapOf("x" to 0, "y" to 2), parsed.intVarNames)
        assertEquals(mapOf("r" to 1), parsed.realVarNames)
        assertEquals(3, parsed.sourceRealCount)
        assertTrue(parsed.hasNonlinearTerms)
    }

    @Test
    fun `integer helper definitions retain lexical scope across caller shadowing`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (define-fun square ((y Int)) Int (* x y)) " +
                "(assert (let ((x 99)) (= (square 2) 6)))",
            allowIntegers = true,
        )

        val atom = assertIs<PolynomialRealFormula.Atom>(parsed.formula)
        val product = assertIs<PolynomialExpression.Product>(atom.left)
        assertEquals(PolynomialExpression.Variable(SourceRealId(0)), product.operands[0])
    }

    @Test
    fun `integer powers retain source DAG without expanding repeated factors`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (assert (= (^ (^ x 16) 16) 1))", allowIntegers = true,
        )

        val outer = assertIs<PolynomialExpression.Power>(assertIs<PolynomialRealFormula.Atom>(parsed.formula).left)
        assertEquals(16, outer.exponent)
        assertEquals(16, assertIs<PolynomialExpression.Power>(outer.base).exponent)
    }

    @Test
    fun `wide fixed exponents retain a logarithmic exact source graph`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const n Int) (assert (= (^ n 2147483648) 1))", allowIntegers = true,
        )
        var expression = assertIs<PolynomialRealFormula.Atom>(parsed.formula).left

        repeat(31) {
            val square = assertIs<PolynomialExpression.Power>(expression)
            assertEquals(2, square.exponent)
            expression = square.base
        }
        assertEquals(PolynomialExpression.Variable(SourceRealId(0)), expression)
    }

    @Test
    fun `deep nonlinear integer evaluation stays on the heap stack`() {
        var expression = "x"
        repeat(1000) { expression = "(* x $expression)" }

        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (assert (= $expression 0))", allowIntegers = true,
        )

        assertEquals(mapOf("x" to 0), parsed.intVarNames)
        assertTrue(parsed.hasNonlinearTerms)
    }

    @Test
    fun `integer assumptions retain source Boolean correlation`() {
        val parsed = SmtLibPolynomial.parse(
            "(declare-const x Int) (declare-const p Bool) " +
                "(assert (= p (= (* x x) 4))) (check-sat-assuming (p))",
            allowIntegers = true,
        )

        assertEquals(mapOf("p" to 0), parsed.boolVarNames)
        assertIs<PolynomialRealFormula.And>(parsed.formula)
    }

    @Test
    fun `integer helper sorts reject fractional real arguments and results`() {
        val sources = listOf(
            "(define-fun f ((x Int)) Int (* x x)) (assert (= (f 1.5) 1))",
            "(define-fun f () Int (/ 1 2)) (assert (= f 1))",
            "(declare-const x Real) (assert (= (to_real x) (* x x)))",
            "(define-fun f () Real 1) (define-fun g ((x Int)) Int x) (assert (= (g f) 1))",
        )

        for (source in sources) {
            assertFailsWith<UnsupportedSmtException> { SmtLibPolynomial.parse(source, allowIntegers = true) }
        }
    }

    @Test
    fun `nonpolynomial integer syntax remains an explicit decline`() {
        val sources = listOf(
            "(declare-const x Int) (assert (= (* x x) (div x 2)))",
            "(declare-const x Int) (assert (= (^ x (- 1)) 2))",
            "(declare-const x Int) (assert (= (^ x x) 2))",
            "(declare-const x Int) (assert (= (/ x x) 2))",
        )

        for (source in sources) {
            assertFailsWith<UnsupportedSmtException> { SmtLibPolynomial.parse(source, allowIntegers = true) }
        }
    }
}
