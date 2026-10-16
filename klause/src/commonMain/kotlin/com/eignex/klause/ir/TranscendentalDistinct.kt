package com.eignex.klause.ir

internal class TranscendentalDistinct(operands: List<TranscendentalExpression>) : TranscendentalFormula {
    private val values = operands.toTypedArray()
    val operands: List<TranscendentalExpression> get() = values.toList()
    private val hash = 31 * values.contentHashCode() + 7
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is TranscendentalDistinct && values.contentEquals(other.values)
}
