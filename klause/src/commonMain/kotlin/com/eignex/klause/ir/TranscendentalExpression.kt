package com.eignex.klause.ir

internal enum class TranscendentalFunction { EXP, LOG, SIN, COS }

internal sealed interface TranscendentalExpression {
    data class Constant(val value: PolynomialExpression.Constant) : TranscendentalExpression
    data class Variable(val id: SourceRealId) : TranscendentalExpression
    data object Pi : TranscendentalExpression
    data class Function(
        val function: TranscendentalFunction,
        val argument: TranscendentalExpression,
    ) : TranscendentalExpression
    class Sum(operands: List<TranscendentalExpression>) : TranscendentalExpression {
        private val captured = operands.toList()
        val operands: List<TranscendentalExpression> get() = captured.toList()
    }
    class Product(operands: List<TranscendentalExpression>) : TranscendentalExpression {
        private val captured = operands.toList()
        val operands: List<TranscendentalExpression> get() = captured.toList()
    }
    data class Power(val base: TranscendentalExpression, val exponent: Int) : TranscendentalExpression {
        init { require(exponent >= 0) }
    }
    data class Conditional(
        val condition: TranscendentalFormula,
        val whenTrue: TranscendentalExpression,
        val whenFalse: TranscendentalExpression,
    ) : TranscendentalExpression
}

internal sealed interface TranscendentalFormula {
    data class Constant(val value: Boolean) : TranscendentalFormula
    data class BooleanVariable(val id: SourceBoolId) : TranscendentalFormula
    data class Atom(
        val left: TranscendentalExpression,
        val relation: PolynomialRelation,
        val right: TranscendentalExpression,
    ) : TranscendentalFormula
    data class Not(val operand: TranscendentalFormula) : TranscendentalFormula
    class And(operands: List<TranscendentalFormula>) : TranscendentalFormula {
        private val captured = operands.toList()
        val operands: List<TranscendentalFormula> get() = captured.toList()
    }
    class Or(operands: List<TranscendentalFormula>) : TranscendentalFormula {
        private val captured = operands.toList()
        val operands: List<TranscendentalFormula> get() = captured.toList()
    }
}
