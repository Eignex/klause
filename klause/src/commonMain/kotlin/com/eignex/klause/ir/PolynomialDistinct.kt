package com.eignex.klause.ir

internal class PolynomialDistinct(operands: List<PolynomialExpression>) : PolynomialRealFormula {
    private val values = operands.toTypedArray()
    val operands: List<PolynomialExpression> get() = values.toList()
    private val hash = 31 * values.contentHashCode() + 7
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is PolynomialDistinct && values.contentEquals(other.values)
}
