package com.eignex.klause.theory.transcendental

import com.eignex.klause.arithmetic.transcendental.RationalEnclosure
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.expRelaxation
import com.eignex.klause.arithmetic.transcendental.logRelaxation
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.and
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.constant
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.not
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.or
import com.eignex.klause.lowering.PolynomialRealLowering.Companion.relation
import com.eignex.klause.simplex.exact.BigFraction

internal class TranscendentalRelaxationModel(
    firstAuxiliary: Int,
    private val arithmetic: TranscendentalArithmetic,
    private val boxes: Map<Int, RationalEnclosure> = emptyMap(),
    private val terms: Int = 8,
) {
    private val lowering = PolynomialRealLowering(firstAuxiliary)
    private val conditions = ArrayList<PolynomialRealFormula>()
    private var next = firstAuxiliary
    val numericCount: Int get() = maxOf(next, lowering.realCount)

    fun formula(source: TranscendentalFormula): PolynomialRealFormula {
        val result = boolean(source, PolynomialRealFormula.Constant(true))
        return lowering.formula(listOf(result) + conditions)
    }

    private fun enforce(guard: PolynomialRealFormula, consequence: PolynomialRealFormula) {
        conditions.add(or(listOf(not(guard), consequence)))
    }

    private fun fresh(): PolynomialExpression.Variable {
        arithmetic.step()
        next = maxOf(next, lowering.realCount)
        return PolynomialExpression.Variable(SourceRealId(next++))
    }

    private fun boolean(source: TranscendentalFormula, guard: PolynomialRealFormula): PolynomialRealFormula {
        arithmetic.step()
        return when (source) {
            is TranscendentalFormula.Constant -> PolynomialRealFormula.Constant(source.value)
            is TranscendentalFormula.BooleanVariable -> PolynomialRealFormula.BooleanVariable(source.id)
            is TranscendentalFormula.Atom -> relation(
                expression(source.left, guard).first, source.relation, expression(source.right, guard).first,
            )
            is TranscendentalFormula.Not -> not(boolean(source.operand, guard))
            is TranscendentalFormula.And -> and(source.operands.map { boolean(it, guard) })
            is TranscendentalFormula.Or -> or(source.operands.map { boolean(it, guard) })
        }
    }

    private fun expression(
        source: TranscendentalExpression,
        guard: PolynomialRealFormula,
    ): Pair<PolynomialExpression, RationalEnclosure?> {
        arithmetic.step()
        return when (source) {
            is TranscendentalExpression.Constant -> source.value to RationalEnclosure.point(
                BigFraction.of(source.value.numerator, source.value.denominator),
            )
            is TranscendentalExpression.Variable -> PolynomialExpression.Variable(source.id) to boxes[source.id.index]
            TranscendentalExpression.Pi -> {
                val value = fresh()
                val enclosure = arithmetic.pi(terms)
                range(value, enclosure)
                value to enclosure
            }
            is TranscendentalExpression.Sum, is TranscendentalExpression.Product -> {
                val children = when (source) {
                    is TranscendentalExpression.Sum -> source.operands
                    is TranscendentalExpression.Product -> source.operands
                    else -> error("unreachable")
                }.map { expression(it, guard) }
                val product = source is TranscendentalExpression.Product
                val neutral = if (product) BigFraction.ONE else BigFraction.ZERO
                var enclosure: RationalEnclosure? = RationalEnclosure.point(neutral)
                for ((_, child) in children) {
                    enclosure = if (enclosure == null || child == null) null
                    else if (product) arithmetic.multiply(enclosure, child) else arithmetic.add(enclosure, child)
                }
                val polynomial = if (product) PolynomialExpression.Product(children.map { it.first })
                else PolynomialExpression.Sum(children.map { it.first })
                polynomial to enclosure
            }
            is TranscendentalExpression.Power -> {
                val (base, enclosure) = expression(source.base, guard)
                PolynomialExpression.Power(base, source.exponent) to enclosure?.let {
                    arithmetic.power(it, source.exponent)
                }
            }
            is TranscendentalExpression.Function -> function(source, guard)
            is TranscendentalExpression.Conditional -> {
                val condition = boolean(source.condition, guard)
                val first = expression(source.whenTrue, and(listOf(guard, condition)))
                val second = expression(source.whenFalse, and(listOf(guard, not(condition))))
                val value = fresh()
                enforce(and(listOf(guard, condition)), relation(value, PolynomialRelation.EQ, first.first))
                enforce(and(listOf(guard, not(condition))), relation(value, PolynomialRelation.EQ, second.first))
                val left = first.second
                val right = second.second
                val enclosure = if (left == null || right == null) null else RationalEnclosure(
                    if (left.lower <= right.lower) left.lower else right.lower,
                    if (left.upper >= right.upper) left.upper else right.upper,
                )
                value to enclosure
            }
        }
    }

    private fun range(value: PolynomialExpression, enclosure: RationalEnclosure) {
        conditions.add(relation(value, PolynomialRelation.GE, constant(enclosure.lower)))
        conditions.add(relation(value, PolynomialRelation.LE, constant(enclosure.upper)))
    }

    private fun function(
        source: TranscendentalExpression.Function,
        guard: PolynomialRealFormula,
    ): Pair<PolynomialExpression, RationalEnclosure?> {
        val (argument, box) = expression(source.argument, guard)
        val value = fresh()
        when (source.function) {
            TranscendentalFunction.EXP -> conditions.add(
                relation(value, PolynomialRelation.GT, constant(BigFraction.ZERO)),
            )
            TranscendentalFunction.LOG -> enforce(
                guard, relation(argument, PolynomialRelation.GT, constant(BigFraction.ZERO)),
            )
            TranscendentalFunction.SIN, TranscendentalFunction.COS -> range(value, RationalEnclosure.UNIT)
        }
        val enclosure = when (source.function) {
            TranscendentalFunction.EXP -> box?.let { arithmetic.exp(it, terms) }
            TranscendentalFunction.LOG -> box?.takeIf { it.lower.signum() > 0 }?.let { arithmetic.log(it, terms) }
            TranscendentalFunction.SIN -> box?.let { arithmetic.sin(it, terms) } ?: RationalEnclosure.UNIT
            TranscendentalFunction.COS -> box?.let { arithmetic.cos(it, terms) } ?: RationalEnclosure.UNIT
        }
        if (enclosure != null) range(value, enclosure)
        if (box != null && !box.isPoint) {
            val anchor = arithmetic.divide(arithmetic.add(box.lower, box.upper), BigFraction.ofLong(2))
            val line = when (source.function) {
                TranscendentalFunction.EXP -> arithmetic.expRelaxation(box, anchor, terms)
                TranscendentalFunction.LOG -> if (box.lower.signum() > 0) {
                    arithmetic.logRelaxation(box, anchor, terms)
                } else null
                else -> null
            }
            if (line != null) {
                fun affine(slope: BigFraction, intercept: BigFraction): PolynomialExpression = PolynomialExpression.Sum(
                    listOf(PolynomialExpression.Product(listOf(constant(slope), argument)), constant(intercept)),
                )
                conditions.add(relation(value, PolynomialRelation.GE, affine(line.lowerSlope, line.lowerIntercept)))
                conditions.add(relation(value, PolynomialRelation.LE, affine(line.upperSlope, line.upperIntercept)))
            }
        }
        return value to enclosure
    }
}
