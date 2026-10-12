package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialRealLowering(firstAuxiliaryReal: Int) {
    var realCount = firstAuxiliaryReal
        private set
    private val definitions = ArrayList<PolynomialRealFormula>()

    fun formula(assertions: List<PolynomialRealFormula>): PolynomialRealFormula = and(assertions + definitions)

    fun conditional(
        condition: PolynomialRealFormula,
        whenTrue: PolynomialExpression,
        whenFalse: PolynomialExpression,
    ): PolynomialExpression {
        if (condition is PolynomialRealFormula.Constant) return if (condition.value) whenTrue else whenFalse
        val value = PolynomialExpression.Variable(SourceRealId(realCount++))
        definitions.add(or(listOf(not(condition), relation(value, PolynomialRelation.EQ, whenTrue))))
        definitions.add(or(listOf(condition, relation(value, PolynomialRelation.EQ, whenFalse))))
        return value
    }

    companion object {
        fun constant(value: BigFraction): PolynomialExpression.Constant =
            PolynomialExpression.Constant.of(value.num, value.den)

        fun rational(value: PolynomialExpression): BigFraction? =
            (value as? PolynomialExpression.Constant)?.let { BigFraction.of(it.numerator, it.denominator) }

        fun sum(
            operands: List<PolynomialExpression>,
            cancellation: Cancellation = Cancellation.Never,
        ): PolynomialExpression {
            if (operands.all { it is PolynomialExpression.Constant }) {
                return constant(
                    operands.fold(BigFraction.ZERO) { value, operand ->
                        foldCoefficient(value, rational(operand)!!, cancellation, multiply = false)
                    },
                )
            }
            return if (operands.size == 1) operands.single() else PolynomialExpression.Sum(operands)
        }

        fun product(
            operands: List<PolynomialExpression>,
            cancellation: Cancellation = Cancellation.Never,
        ): PolynomialExpression {
            if (operands.all { it is PolynomialExpression.Constant }) {
                return constant(
                    operands.fold(BigFraction.ONE) { value, operand ->
                        foldCoefficient(value, rational(operand)!!, cancellation, multiply = true)
                    },
                )
            }
            return if (operands.size == 1) operands.single() else PolynomialExpression.Product(operands)
        }

        fun negate(
            operand: PolynomialExpression,
            cancellation: Cancellation = Cancellation.Never,
        ): PolynomialExpression =
            product(listOf(constant(BigFraction.MINUS_ONE), operand), cancellation)

        fun power(
            base: PolynomialExpression,
            exponent: Int,
            cancellation: Cancellation = Cancellation.Never,
        ): PolynomialExpression {
            require(exponent >= 0) { "negative polynomial exponent" }
            return when (exponent) {
                0 -> constant(BigFraction.ONE)
                1 -> base
                else -> {
                    val coefficient = rational(base) ?: return PolynomialExpression.Power(base, exponent)
                    val minimumBits = maxOf(
                        coefficient.num.magnitudeBitLength(), coefficient.den.magnitudeBitLength(),
                    ) - 1L
                    if (minimumBits * exponent + 1 > Int.MAX_VALUE) return PolynomialExpression.Power(base, exponent)
                    var value = BigFraction.ONE
                    var factor = coefficient
                    var remaining = exponent
                    while (remaining > 0) {
                        if (remaining and 1 != 0) value = foldCoefficient(value, factor, cancellation, multiply = true)
                        remaining = remaining ushr 1
                        if (remaining > 0) factor = foldCoefficient(factor, factor, cancellation, multiply = true)
                    }
                    constant(value)
                }
            }
        }

        private fun foldCoefficient(
            left: BigFraction,
            right: BigFraction,
            cancellation: Cancellation,
            multiply: Boolean,
        ): BigFraction {
            cancellation.polynomialSourceCheckpoint()
            val value = if (multiply) left * right else left + right
            cancellation.polynomialSourceCheckpoint(1)
            return value
        }

        fun relation(
            left: PolynomialExpression,
            op: PolynomialRelation,
            right: PolynomialExpression,
        ): PolynomialRealFormula = PolynomialRealFormula.Atom(left, op, right)

        fun not(operand: PolynomialRealFormula): PolynomialRealFormula = when (operand) {
            is PolynomialRealFormula.Constant -> PolynomialRealFormula.Constant(!operand.value)
            is PolynomialRealFormula.Not -> operand.operand
            else -> PolynomialRealFormula.Not(operand)
        }

        fun and(operands: List<PolynomialRealFormula>): PolynomialRealFormula {
            if (operands.any { it == PolynomialRealFormula.Constant(false) }) {
                return PolynomialRealFormula.Constant(false)
            }
            val values = operands.filter { it != PolynomialRealFormula.Constant(true) }
            return when (values.size) {
                0 -> PolynomialRealFormula.Constant(true)
                1 -> values.single()
                else -> PolynomialRealFormula.And(values)
            }
        }

        fun or(operands: List<PolynomialRealFormula>): PolynomialRealFormula {
            if (operands.any { it == PolynomialRealFormula.Constant(true) }) return PolynomialRealFormula.Constant(true)
            val values = operands.filter { it != PolynomialRealFormula.Constant(false) }
            return when (values.size) {
                0 -> PolynomialRealFormula.Constant(false)
                1 -> values.single()
                else -> PolynomialRealFormula.Or(values)
            }
        }

        fun equivalent(left: PolynomialRealFormula, right: PolynomialRealFormula): PolynomialRealFormula =
            and(listOf(or(listOf(not(left), right)), or(listOf(left, not(right)))))

        fun conditional(
            condition: PolynomialRealFormula,
            whenTrue: PolynomialRealFormula,
            whenFalse: PolynomialRealFormula,
        ): PolynomialRealFormula = or(listOf(and(listOf(condition, whenTrue)), and(listOf(not(condition), whenFalse))))
    }
}

internal class PolynomialSourceInterrupted : RuntimeException("polynomial source lowering interrupted")

internal fun Cancellation.polynomialSourceCheckpoint(units: Long = 0) {
    if (isCancelled()) throw PolynomialSourceInterrupted()
    if (units > 0) charge(units)
    if (isCancelled()) throw PolynomialSourceInterrupted()
}
