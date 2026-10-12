package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialConditional
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.lowerQuadraticObjective
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation
import com.eignex.klause.simplex.exact.BigFraction

internal object SmtLibQuadraticObjective {
    fun capture(
        expression: PolynomialExpression,
        integers: Map<String, Int>,
        reals: Map<String, Int>,
        boolCount: Int,
        numericCount: Int,
        conditionals: List<PolynomialConditional>,
        cancellation: Cancellation,
    ): QuadraticObjectiveSpec {
        val variables = mutableMapOf<Int, QuadraticVariable>()
        for ((index, coordinate) in integers.values.withIndex()) {
            cancellation.polynomialSourceCheckpoint()
            variables[coordinate] = QuadraticVariable.Int(SourceIntId(index))
        }
        for ((index, coordinate) in reals.values.withIndex()) {
            cancellation.polynomialSourceCheckpoint()
            variables[coordinate] = QuadraticVariable.Real(SourceRealId(index))
        }
        val virtual = (0 until boolCount).associateWith { id ->
            cancellation.polynomialSourceCheckpoint()
            if (numericCount > Int.MAX_VALUE - id) smtUnsupported("quadratic objective coordinate limit")
            val coordinate = numericCount + id
            variables[coordinate] = QuadraticVariable.Bool(SourceBoolId(id))
            PolynomialExpression.Variable(SourceRealId(coordinate))
        }
        val replacements = mutableMapOf<Int, PolynomialExpression>()
        for (conditional in conditionals) {
            cancellation.polynomialSourceCheckpoint()
            var condition = conditional.condition
            var whenTrue = conditional.whenTrue
            var whenFalse = conditional.whenFalse
            if (condition is PolynomialRealFormula.Not) {
                condition = condition.operand
                val swapped = whenTrue
                whenTrue = whenFalse
                whenFalse = swapped
            }
            if (condition !is PolynomialRealFormula.BooleanVariable || condition.id.index !in virtual) continue
            replacements[conditional.coordinate] = PolynomialExpression.Sum(listOf(
                whenFalse, PolynomialExpression.Product(listOf(
                    virtual.getValue(condition.id.index), PolynomialExpression.Sum(listOf(
                        whenTrue, PolynomialExpression.Product(listOf(
                            PolynomialRealLowering.constant(BigFraction.MINUS_ONE), whenFalse,
                        )),
                    )),
                )),
            ))
        }
        val values = mutableMapOf<PolynomialExpression, PolynomialExpression>()
        val work = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
        work.addLast(expression to false)
        while (work.isNotEmpty()) {
            cancellation.polynomialSourceCheckpoint()
            val (node, combine) = work.removeLast()
            if (node in values) continue
            val children = when (node) {
                is PolynomialExpression.Sum -> node.operands
                is PolynomialExpression.Product -> node.operands
                is PolynomialExpression.Power -> listOf(node.base)
                is PolynomialExpression.Variable -> replacements[node.id.index]?.let(::listOf) ?: emptyList()
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                work.addLast(node to true)
                for (child in children.asReversed()) work.addLast(child to false)
                continue
            }
            values[node] = when (node) {
                is PolynomialExpression.Constant -> node
                is PolynomialExpression.Variable -> replacements[node.id.index]?.let(values::getValue) ?: node
                is PolynomialExpression.Sum -> PolynomialExpression.Sum(children.map(values::getValue))
                is PolynomialExpression.Product -> PolynomialExpression.Product(children.map(values::getValue))
                is PolynomialExpression.Power -> PolynomialExpression.Power(values.getValue(node.base), node.exponent)
            }
        }
        return lowerQuadraticObjective(values.getValue(expression), variables, cancellation)
            ?: smtUnsupported("objective must be quadratic over original source coordinates")
    }
}
