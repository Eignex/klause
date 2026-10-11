package com.eignex.klause.ir

import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolynomialExpressionTest {
    @Test
    fun `structural equality retains shared repeated subexpressions`() {
        var first: PolynomialExpression = PolynomialExpression.Variable(SourceRealId(0))
        var second: PolynomialExpression = PolynomialExpression.Variable(SourceRealId(0))
        repeat(30) {
            first = PolynomialExpression.Product(listOf(first, first))
            second = PolynomialExpression.Product(listOf(second, second))
        }

        assertEquals(first, second)
    }

    @Test
    fun `separately built deep equal structures compare without call stack recursion`() {
        var first: PolynomialExpression = PolynomialExpression.Variable(SourceRealId(0))
        var second: PolynomialExpression = PolynomialExpression.Variable(SourceRealId(0))
        repeat(1000) {
            first = PolynomialExpression.Sum(listOf(first))
            second = PolynomialExpression.Sum(listOf(second))
        }

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `rational coefficient identity uses reduced values and a positive denominator`() {
        val reduced = PolynomialExpression.Constant.of(parseBigInt("123456789012345678901234567890"), bigIntOf(8))
        val equivalent = PolynomialExpression.Constant.of(parseBigInt("-246913578024691357802469135780"), bigIntOf(-16))

        assertEquals(reduced, equivalent)
        assertEquals(reduced.hashCode(), equivalent.hashCode())
    }

    @Test
    fun `zero coefficients have one structural identity`() {
        val zero = PolynomialExpression.Constant.of(bigIntOf(0))
        val fraction = PolynomialExpression.Constant.of(bigIntOf(0), bigIntOf(-29))

        assertEquals(zero, fraction)
    }

    @Test
    fun `polynomial operand storage is independent of caller mutations`() {
        val variable = PolynomialExpression.Variable(SourceRealId(0))
        val operands = mutableListOf<PolynomialExpression>(variable, variable)
        val sum = PolynomialExpression.Sum(operands)
        val product = PolynomialExpression.Product(operands)

        operands[0] = PolynomialExpression.Constant.of(bigIntOf(9))
        (sum.operands as? MutableList)?.set(0, operands[0])
        (product.operands as? MutableList)?.set(0, operands[0])

        assertEquals(listOf(variable, variable), sum.operands)
        assertEquals(listOf(variable, variable), product.operands)
    }

    @Test
    fun `alias remaps retain repeated variables and exact signed coefficients`() {
        val coefficient = PolynomialExpression.Constant.of(bigIntOf(-1), bigIntOf(3))
        val expression = PolynomialExpression.Product(
            listOf(
                coefficient, PolynomialExpression.Variable(SourceRealId(0)),
                PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(1)), 3),
            ),
        )
        val mapping = VarRemap(intArrayOf(), intArrayOf(), intArrayOf(4, 4))

        val remapped = expression.remap(mapping)

        assertEquals(
            PolynomialExpression.Product(
                listOf(
                    coefficient, PolynomialExpression.Variable(SourceRealId(4)),
                    PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(4)), 3),
                ),
            ),
            remapped,
        )
    }

    @Test
    fun `removing a referenced polynomial real is rejected`() {
        val expression = PolynomialExpression.Variable(SourceRealId(0))

        assertFailsWith<IllegalArgumentException> {
            expression.remap(VarRemap(intArrayOf(), intArrayOf(), intArrayOf(-1)))
        }
    }

    @Test
    fun `negative powers are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), -1)
        }
    }

    @Test
    fun `zero coefficient denominators are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PolynomialExpression.Constant.of(bigIntOf(1), bigIntOf(0))
        }
    }
}
