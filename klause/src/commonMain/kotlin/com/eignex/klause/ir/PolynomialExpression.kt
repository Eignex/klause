package com.eignex.klause.ir

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.signum
import com.eignex.klause.util.unaryMinus

/** Immutable engine-neutral polynomial syntax over the coordinate scope supplied by its owner. */
sealed interface PolynomialExpression {
    /** Reduced rational coefficient with a positive denominator. */
    class Constant private constructor(
        /** Exact numerator. */
        val numerator: BigInt,
        /** Exact positive denominator. */
        val denominator: BigInt,
    ) : PolynomialExpression {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Constant && numerator == other.numerator && denominator == other.denominator)

        override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

        /** Exact coefficient construction. */
        companion object {
            /** Normalize a rational coefficient without floating conversion. */
            fun of(numerator: BigInt, denominator: BigInt = BIG_ONE): Constant {
                require(!denominator.isZero()) { "zero polynomial coefficient denominator" }
                val divisor = numerator.gcd(denominator)
                val sign = if (denominator.signum() < 0) -divisor else divisor
                return Constant(numerator / sign, denominator / sign)
            }
        }
    }

    /** A coordinate in the owning source or private projection scope. */
    data class Variable(
        /** Typed coordinate within that scope. */
        val id: SourceRealId,
    ) : PolynomialExpression

    /** An ordered sum; an empty sum denotes zero. */
    class Sum(operands: List<PolynomialExpression>) : PolynomialExpression {
        private val values = operands.toTypedArray()
        internal val operandCount: Int get() = values.size
        /** Captured operands in syntax order. */
        val operands: List<PolynomialExpression> get() = values.toList()
        override fun equals(other: Any?): Boolean = other is Sum && polynomialExpressionsEqual(this, other)
        private val hash = 31 * values.contentHashCode() + 1
        override fun hashCode(): Int = hash
    }

    /** An ordered product; an empty product denotes one. */
    class Product(operands: List<PolynomialExpression>) : PolynomialExpression {
        private val values = operands.toTypedArray()
        internal val operandCount: Int get() = values.size
        /** Captured operands in syntax order. */
        val operands: List<PolynomialExpression> get() = values.toList()
        override fun equals(other: Any?): Boolean = other is Product && polynomialExpressionsEqual(this, other)
        private val hash = 31 * values.contentHashCode() + 2
        override fun hashCode(): Int = hash
    }

    /** A nonnegative fixed integer exponent in neutral polynomial syntax. */
    class Power(
        /** Base expression. */
        val base: PolynomialExpression,
        /** Nonnegative exponent. */
        val exponent: Int,
    ) : PolynomialExpression {
        private val hash = 31 * base.hashCode() + exponent
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is Power && polynomialExpressionsEqual(this, other)
        init {
            require(exponent >= 0) { "negative polynomial exponent" }
        }
    }
}

internal fun PolynomialExpression.remap(remap: VarRemap): PolynomialExpression {
    val work = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
    val values = ArrayDeque<PolynomialExpression>()
    val remapped = HashMap<PolynomialExpression, PolynomialExpression>()
    work.addLast(this to false)
    while (work.isNotEmpty()) {
        val (node, combine) = work.removeLast()
        val retained = remapped[node]
        if (!combine && retained != null) {
            values.addLast(retained)
            continue
        }
        when (node) {
            is PolynomialExpression.Constant -> values.addLast(node)
            is PolynomialExpression.Variable -> values.addLast(
                PolynomialExpression.Variable(requireNotNull(remap.real(node.id)) { "removed polynomial variable" }),
            )
            is PolynomialExpression.Power -> if (combine) {
                values.addLast(PolynomialExpression.Power(values.removeLast(), node.exponent))
            } else {
                work.addLast(node to true)
                work.addLast(node.base to false)
            }
            is PolynomialExpression.Sum, is PolynomialExpression.Product -> {
                val operands = when (node) {
                    is PolynomialExpression.Sum -> node.operands
                    is PolynomialExpression.Product -> node.operands
                }
                if (combine) {
                    val args = List(operands.size) { values.removeLast() }.reversed()
                    values.addLast(
                        if (node is PolynomialExpression.Sum) PolynomialExpression.Sum(args)
                        else PolynomialExpression.Product(args),
                    )
                } else {
                    work.addLast(node to true)
                    for (operand in operands.asReversed()) work.addLast(operand to false)
                }
            }
        }
        if (combine || node is PolynomialExpression.Constant || node is PolynomialExpression.Variable) {
            remapped[node] = values.last()
        }
    }
    return values.single()
}

internal fun polynomialExpressionsEqual(left: PolynomialExpression, right: PolynomialExpression): Boolean {
    val work = ArrayDeque<Pair<PolynomialExpression, PolynomialExpression>>()
    val seen = HashSet<PolynomialNodePair<PolynomialExpression>>()
    work.addLast(left to right)
    while (work.isNotEmpty()) {
        val (first, second) = work.removeLast()
        if (first === second || !seen.add(PolynomialNodePair(first, second))) continue
        when (first) {
            is PolynomialExpression.Constant -> {
                if (second !is PolynomialExpression.Constant || first != second) return false
            }
            is PolynomialExpression.Variable -> {
                if (second !is PolynomialExpression.Variable || first.id != second.id) return false
            }
            is PolynomialExpression.Power -> {
                if (second !is PolynomialExpression.Power || first.exponent != second.exponent) return false
                work.addLast(first.base to second.base)
            }
            is PolynomialExpression.Sum, is PolynomialExpression.Product -> {
                val firstOperands: List<PolynomialExpression>
                val secondOperands: List<PolynomialExpression>
                when (first) {
                    is PolynomialExpression.Sum -> {
                        if (second !is PolynomialExpression.Sum) return false
                        firstOperands = first.operands
                        secondOperands = second.operands
                    }
                    is PolynomialExpression.Product -> {
                        if (second !is PolynomialExpression.Product) return false
                        firstOperands = first.operands
                        secondOperands = second.operands
                    }
                }
                if (firstOperands.size != secondOperands.size) return false
                for (index in firstOperands.indices) work.addLast(firstOperands[index] to secondOperands[index])
            }
        }
    }
    return true
}

internal class PolynomialNodePair<T : Any>(private val first: T, private val second: T) {
    override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()
    override fun equals(other: Any?): Boolean =
        other is PolynomialNodePair<*> && first === other.first && second === other.second
}
