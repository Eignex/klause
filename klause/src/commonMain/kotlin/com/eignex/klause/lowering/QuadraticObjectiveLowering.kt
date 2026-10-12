package com.eignex.klause.lowering

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

private typealias QuadraticTerms = Map<List<Int>, BigFraction>

internal fun lowerQuadraticObjective(
    expression: PolynomialExpression,
    sourceVariables: Map<Int, QuadraticVariable>,
    cancellation: Cancellation = Cancellation.Never,
): QuadraticObjectiveSpec? {
    fun step() = cancellation.polynomialSourceCheckpoint(1)
    fun add(target: MutableMap<List<Int>, BigFraction>, powers: List<Int>, coefficient: BigFraction) {
        step()
        val combined = (target[powers] ?: BigFraction.ZERO) + coefficient
        if (combined == BigFraction.ZERO) target.remove(powers) else target[powers] = combined
    }
    fun multiply(left: QuadraticTerms, right: QuadraticTerms): QuadraticTerms? {
        val result = LinkedHashMap<List<Int>, BigFraction>()
        for ((first, a) in left) for ((second, b) in right) {
            step()
            if (first.size + second.size > 2) return null
            add(result, (first + second).sorted(), a * b)
        }
        return result
    }
    val unit: QuadraticTerms = mapOf(emptyList<Int>() to BigFraction.ONE)
    val values = HashMap<PolynomialExpression, QuadraticTerms>()
    val work = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
    work.addLast(expression to false)
    while (work.isNotEmpty()) {
        step()
        val (node, combine) = work.removeLast()
        if (node in values) continue
        val children = when (node) {
            is PolynomialExpression.Sum -> node.operands
            is PolynomialExpression.Product -> node.operands
            is PolynomialExpression.Power -> listOf(node.base)
            else -> emptyList()
        }
        if (!combine && children.isNotEmpty()) {
            work.addLast(node to true)
            for (child in children.asReversed()) work.addLast(child to false)
            continue
        }
        values[node] = when (node) {
            is PolynomialExpression.Constant -> {
                val value = BigFraction.of(node.numerator, node.denominator)
                if (value == BigFraction.ZERO) emptyMap() else mapOf(emptyList<Int>() to value)
            }
            is PolynomialExpression.Variable -> {
                if (node.id.index !in sourceVariables) return null
                mapOf(listOf(node.id.index) to BigFraction.ONE)
            }
            is PolynomialExpression.Sum -> LinkedHashMap<List<Int>, BigFraction>().also { result ->
                for (child in children) for ((powers, coefficient) in values.getValue(child)) {
                    add(result, powers, coefficient)
                }
            }
            is PolynomialExpression.Product -> {
                var product = unit
                for (child in children) product = multiply(product, values.getValue(child)) ?: return null
                product
            }
            is PolynomialExpression.Power -> {
                var power = unit
                var factor = values.getValue(node.base)
                var exponent = node.exponent
                while (exponent > 0) {
                    step()
                    if (exponent and 1 != 0) power = multiply(power, factor) ?: return null
                    exponent = exponent ushr 1
                    if (exponent > 0) factor = multiply(factor, factor) ?: return null
                }
                power
            }
        }
    }
    val terms = values.getValue(expression)
    val constant = terms[emptyList()] ?: BigFraction.ZERO
    val objective = terms.filterKeys { it.isNotEmpty() }.map { (powers, coefficient) ->
        step()
        QuadraticObjectiveTerm(
            QuadraticCoefficient.of(coefficient.num, coefficient.den), sourceVariables.getValue(powers[0]),
            powers.getOrNull(1)?.let(sourceVariables::getValue),
        )
    }
    step()
    return QuadraticObjectiveSpec(objective, QuadraticCoefficient.of(constant.num, constant.den))
}
