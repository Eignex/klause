package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.shl
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearestDoublesTest {
    private fun root(index: Int, vararg coefficients: BigFraction): AlgebraicReal =
        AlgebraicArithmetic().roots(RationalPolynomial(coefficients.toList()))[index - 1]

    private fun square(value: BigFraction, index: Int = 2) =
        root(index, value.negated(), BigFraction.ZERO, BigFraction.ONE)

    // A root of x^2 - [value] given its isolating interval, sparing the halvings from an isolation bound.
    private fun squareWithin(value: BigFraction, lower: BigFraction, upper: BigFraction) = AlgebraicReal(
        RationalPolynomial(listOf(value.negated(), BigFraction.ZERO, BigFraction.ONE)),
        1,
        RationalInterval(lower, upper),
    )

    // [root] of (x - [root])(x - 1) given an isolating interval, keeping coefficients near the root's own size.
    private fun beside(root: BigFraction, lower: BigFraction, upper: BigFraction) = AlgebraicReal(
        RationalPolynomial(listOf(root, (root + BigFraction.ONE).negated(), BigFraction.ONE)),
        1,
        RationalInterval(lower, upper),
    )

    private fun tenTo(exponent: Int) = parseBigInt("1" + "0".repeat(exponent))

    private fun power(exponent: Int) = if (exponent >= 0) {
        BigFraction.of(BIG_ONE shl exponent, BIG_ONE)
    } else {
        BigFraction.of(BIG_ONE, BIG_ONE shl -exponent)
    }

    @Test
    fun `narrowing should settle the nearest binary64 value of a root`() {
        val tie = BigFraction.ONE + BigFraction.ofLong(3) * power(-53)
        val five = BigFraction.ofLong(5)
        val cases = listOf(
            "one" to (square(BigFraction.ONE) to 1.0),
            "sqrt two" to (square(BigFraction.ofLong(2)) to sqrt(2.0)),
            "negative sqrt three" to (square(BigFraction.ofLong(3), 1) to -sqrt(3.0)),
            "one third" to (square(BigFraction.ofLong(9).reciprocal()) to 1.0 / 3.0),
            "tie" to (root(2, five.negated() * tie, five - tie, BigFraction.ONE) to 1.0.nextUp().nextUp()),
            "negative tie" to (root(1, five.negated() * tie, tie - five, BigFraction.ONE) to -1.0.nextUp().nextUp()),
            "large integer" to (squareWithin(BigFraction.of(tenTo(40), BIG_ONE), power(66), power(67)) to 1.0E20),
            "subnormal" to (beside(power(-1074), power(-1075), power(-1073)) to Double.MIN_VALUE),
            "negative below the smallest subnormal" to
                (beside(power(-1076).negated(), power(-1075).negated(), BigFraction.ZERO) to -0.0),
        )
        for ((name, case) in cases) {
            val (value, nearest) = case

            val narrowed = narrowToNearestDoubles(listOf(value), ExecutionControl.Never).single()

            assertEquals(nearest.toRawBits(), narrowed.nearestDouble?.toRawBits(), name)
        }
    }

    @Test
    fun `a rational should round to its nearest binary64 value from a seed several units off`() {
        fun off(value: Double, steps: Int): Double {
            var seed = value
            repeat(steps) { seed = seed.nextUp() }
            return seed
        }
        val overflow = requireNotNull(BigFraction.ofDouble(Double.MAX_VALUE)) + power(970)
        val cases = listOf(
            "one third" to Triple(BigFraction.ofLong(3).reciprocal(), off(1.0 / 3.0, 5), 1.0 / 3.0),
            "tie to an even one" to Triple(BigFraction.ONE + power(-53), off(1.0.nextDown(), 1), 1.0),
            "tie to an even neighbour" to
                Triple(BigFraction.ONE + BigFraction.ofLong(3) * power(-53), 1.0, 1.0.nextUp().nextUp()),
            "overflow at the tie" to Triple(overflow, Double.MAX_VALUE.nextDown(), Double.POSITIVE_INFINITY),
            "largest below the tie" to Triple(overflow - power(900), Double.POSITIVE_INFINITY, Double.MAX_VALUE),
            "negative below half the smallest subnormal" to Triple(power(-1076).negated(), Double.MIN_VALUE, -0.0),
        )
        for ((name, case) in cases) {
            val (value, seed, nearest) = case

            val rounded = nearestBinary64(value, seed)

            assertEquals(nearest.toRawBits(), rounded.toRawBits(), name)
        }
    }

    @Test
    fun `a stopped narrowing should keep the narrowest interval it reached`() {
        val value = square(BigFraction.ONE)
        var complete = 0
        narrowToNearestDoubles(listOf(value), ExecutionControl { ++complete < 0 })
        var polls = 0

        val narrowed = narrowToNearestDoubles(listOf(value), ExecutionControl { ++polls > complete / 2 }).single()

        assertNull(narrowed.nearestDouble)
        assertTrue(narrowed.upperBound - narrowed.lowerBound < value.upperBound - value.lowerBound)
    }
}
