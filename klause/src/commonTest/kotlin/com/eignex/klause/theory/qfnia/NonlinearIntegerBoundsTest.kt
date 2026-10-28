package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NonlinearIntegerBoundsTest {
    @Test
    fun `strict wide integer endpoints should exclude the boundary in either source order`() {
        val endpoint = "100000000000000000000"
        val below = PolynomialCondition(
            upper = mapOf(0 to BigFraction.of(parseBigInt("99999999999999999999"), BIG_ONE)),
        )
        val above = PolynomialCondition(
            lower = mapOf(0 to BigFraction.of(parseBigInt("100000000000000000001"), BIG_ONE)),
        )
        val cases = listOf(
            "(< n $endpoint)" to below,
            "(> $endpoint n)" to below,
            "(> n $endpoint)" to above,
            "(< $endpoint n)" to above,
        )
        for ((atom, expected) in cases) {
            val source = problem("(declare-const n Int) (assert $atom)")

            val bounds = source.integerBounds(PolynomialCondition(), PolynomialArithmetic())

            assertEquals(expected, bounds, atom)
        }
    }

    @Test
    fun `fractional endpoints should use signed floor and ceiling for integer bounds`() {
        val cases = listOf(
            "(< n (/ 3 2))" to PolynomialCondition(upper = mapOf(0 to BigFraction.ONE)),
            "(<= n (/ 3 2))" to PolynomialCondition(upper = mapOf(0 to BigFraction.ONE)),
            "(> n (/ 3 2))" to PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(2))),
            "(>= n (/ 3 2))" to PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(2))),
            "(< n (/ (- 3) 2))" to PolynomialCondition(upper = mapOf(0 to BigFraction.ofLong(-2))),
            "(<= n (/ (- 3) 2))" to PolynomialCondition(upper = mapOf(0 to BigFraction.ofLong(-2))),
            "(> n (/ (- 3) 2))" to PolynomialCondition(lower = mapOf(0 to BigFraction.MINUS_ONE)),
            "(>= n (/ (- 3) 2))" to PolynomialCondition(lower = mapOf(0 to BigFraction.MINUS_ONE)),
        )
        for ((atom, expected) in cases) {
            val source = problem("(declare-const n Int) (assert $atom)")

            val bounds = source.integerBounds(PolynomialCondition(), PolynomialArithmetic())

            assertEquals(expected, bounds, atom)
        }
    }

    @Test
    fun `fractional integer equality should produce an empty exact intersection`() {
        val source = problem("(declare-const n Int) (assert (= n (/ (- 3) 2)))")

        val bounds = source.integerBounds(PolynomialCondition(), PolynomialArithmetic())

        assertEquals(
            PolynomialCondition(
                lower = mapOf(0 to BigFraction.MINUS_ONE),
                upper = mapOf(0 to BigFraction.ofLong(-2)),
            ),
            bounds,
        )
    }

    @Test
    fun `rounding conditioned integer windows should preserve original real and Boolean identities`() {
        val source = problem("(declare-const r Real) (declare-const n Int) (declare-const b Bool) (assert b)")
        val fraction = BigFraction.of(bigIntOf(-3), bigIntOf(2))
        val condition = PolynomialCondition(
            bools = mapOf(0 to true),
            lower = mapOf(0 to fraction, 1 to fraction),
            upper = mapOf(0 to BigFraction.ONE, 1 to BigFraction.ONE),
        )

        val bounds = source.integerBounds(condition, PolynomialArithmetic())

        assertEquals(
            PolynomialCondition(
                bools = mapOf(0 to true),
                lower = mapOf(0 to fraction, 1 to BigFraction.MINUS_ONE),
                upper = condition.upper,
            ),
            bounds,
        )
        assertEquals(fraction, condition.lower[1])
    }

    @Test
    fun `Boolean alternatives should supply no unconditional integer bound`() {
        val source = problem(
            "(declare-const n Int) (declare-const b Bool) " +
                "(assert (or (and b (< n 0)) (and (not b) (> n 0))))",
        )
        for (condition in listOf(PolynomialCondition(), PolynomialCondition(bools = mapOf(0 to true)))) {
            val bounds = source.integerBounds(condition, PolynomialArithmetic())

            assertEquals(condition, bounds)
        }
    }

    @Test
    fun `replacement condition should not retain integer windows from an earlier call`() {
        val source = problem("(declare-const n Int) (assert (distinct n 0))")
        val narrow = PolynomialCondition(lower = mapOf(0 to BigFraction.ONE))
        source.integerBounds(narrow, PolynomialArithmetic())
        val widened = PolynomialCondition(upper = mapOf(0 to BigFraction.ZERO))

        val bounds = source.integerBounds(widened, PolynomialArithmetic())

        assertEquals(widened, bounds)
    }

    @Test
    fun `cancelled bound extraction should publish no partial condition`() {
        val source = problem("(declare-const n Int) (assert (> n 0))")

        assertFailsWith<PolynomialInterrupted> {
            source.integerBounds(PolynomialCondition(), PolynomialArithmetic(Cancellation { true }))
        }
    }

    private fun problem(text: String): NonlinearIntegerProblem {
        val parsed = SmtLibPolynomial.parse(text, allowIntegers = true)
        return NonlinearIntegerProblem(
            parsed.formula,
            parsed.realCount,
            parsed.boolCount,
            parsed.intVarNames,
            parsed.realVarNames,
            parsed.boolVarNames,
        )
    }
}
