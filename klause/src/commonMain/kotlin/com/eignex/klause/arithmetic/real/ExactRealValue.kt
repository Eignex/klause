package com.eignex.klause.arithmetic.real

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.simplex.exact.BigFraction

/** Real analytic functions with exact real arguments; log requires a positive argument. */
enum class ExactRealFunction {
    /** Real exponential. */
    EXP,
    /** Natural logarithm on strictly positive reals. */
    LOG,
    /** Real sine in radians. */
    SIN,
    /** Real cosine in radians. */
    COS,
}

/** Immutable mathematical expression describing one authoritative real coordinate. */
sealed interface ExactRealExpression {
    /** Existing exact rational or algebraic scalar. */
    data class Algebraic(
        /** Owned root metadata. */
        val value: AlgebraicReal,
    ) : ExactRealExpression

    /** The positive circle constant. */
    data object Pi : ExactRealExpression

    /** Exact function application; published values have checked domains. */
    data class Function(
        /** Function semantics. */
        val function: ExactRealFunction,
        /** Immutable authoritative argument. */
        val argument: ExactRealExpression,
    ) : ExactRealExpression

    /** Exact addition of all operands. */
    class Sum internal constructor(operands: List<ExactRealExpression>) : ExactRealExpression {
        private val captured = operands.toList()
        /** Independent operand list. */
        val operands: List<ExactRealExpression> get() = captured.toList()
    }

    /** Exact multiplication of all operands. */
    class Product internal constructor(operands: List<ExactRealExpression>) : ExactRealExpression {
        private val captured = operands.toList()
        /** Independent operand list. */
        val operands: List<ExactRealExpression> get() = captured.toList()
    }

    /** Nonnegative integer power. */
    data class Power internal constructor(
        /** Exact base. */
        val base: ExactRealExpression,
        /** Nonnegative exponent. */
        val exponent: Int,
    ) : ExactRealExpression {
        init { require(exponent >= 0) }
    }
}

/** Owned exact real value whose expression retains transcendental coordinate authority. */
class ExactRealValue internal constructor(
    /** Immutable defining expression, including exact algebraic leaves. */
    val expression: ExactRealExpression,
) {
    /** Existing algebraic coordinate when the complete value is algebraic. */
    val algebraic: AlgebraicReal? get() = (expression as? ExactRealExpression.Algebraic)?.value

    /** Exact rational coordinate when the complete value has a point representation. */
    val rational: BigFraction? get() = algebraic?.rational
}
