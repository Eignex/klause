package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

internal inline fun ProblemBuilder.newIntExpression(
    expression: LinComb,
    domain: IntDomain,
    freshInt: (IntDomain) -> Int = { newInt(it) },
    define: (Int, LongArray, IntArray, Long) -> Unit = { _, _, _, _ -> },
): Int {
    val result = freshInt(domain)
    val operands = expression.coeffs.keys.toList()
    val coefficients = LongArray(operands.size + 1) {
        if (it < operands.size) -expression.coeffs.getValue(operands[it]) else 1L
    }
    val variables = IntArray(operands.size + 1) { if (it < operands.size) operands[it] else result }
    factors += Linear(coefficients, variables, LinearOp.EQ, expression.constant)
    define(result, coefficients, variables, expression.constant)
    return result
}

internal inline fun ProblemBuilder.newBooleanInt(
    literal: Int,
    freshInt: (IntDomain) -> Int = { newInt(it) },
): Int {
    val result = freshInt(IntDomain(0, 1))
    factors += ReifiedLinear(Lit.variable(literal), intArrayOf(1), intArrayOf(result), LinearOp.EQ,
        if (Lit.isPositive(literal)) 1 else 0)
    return result
}

internal inline fun ProblemBuilder.newIntSelection(
    index: Int,
    entries: LongArray,
    domain: IntDomain,
    freshInt: (IntDomain) -> Int = { newInt(it) },
): Int {
    val result = freshInt(domain)
    factors += Element(index, result, entries, arrIsVars = true, indexOffset = 0)
    return result
}

internal inline fun ProblemBuilder.newIntExtreme(
    entries: IntArray,
    max: Boolean,
    domain: IntDomain,
    freshInt: (IntDomain) -> Int = { newInt(it) },
): Int {
    val result = freshInt(domain)
    factors += ArrayMinMax(result, entries, max)
    return result
}

internal fun ProblemBuilder.intAbs(operand: Int, result: Int) {
    factors += Abs(operand, result)
}

internal inline fun ProblemBuilder.newIntProduct(
    left: Int,
    right: Int,
    domain: IntDomain,
    freshInt: (IntDomain) -> Int = { newInt(it) },
): Int {
    val result = freshInt(domain)
    factors += Product(left, right, result)
    return result
}

internal fun selectionBounds(entries: IntArray, domains: List<IntDomain>): IntDomain =
    IntDomain(entries.minOf { domains[it].min }, entries.maxOf { domains[it].max })

internal fun extremeBounds(entries: IntArray, domains: List<IntDomain>, max: Boolean): IntDomain = if (max) {
    IntDomain(entries.maxOf { domains[it].min }, entries.maxOf { domains[it].max })
} else {
    IntDomain(entries.minOf { domains[it].min }, entries.minOf { domains[it].max })
}

internal fun absUpperBound(domain: IntDomain): Long = if (domain.min == Long.MIN_VALUE) {
    Long.MAX_VALUE
} else {
    maxOf(kotlin.math.abs(domain.min), kotlin.math.abs(domain.max))
}

internal fun absBounds(domain: IntDomain): IntDomain {
    val lower = when {
        domain.min <= 0L && domain.max >= 0L -> 0L
        domain.min > 0L -> domain.min
        domain.max == Long.MIN_VALUE -> Long.MAX_VALUE
        else -> -domain.max
    }
    return IntDomain(lower, absUpperBound(domain))
}

internal fun linearBounds(expression: LinComb, domains: List<IntDomain>): IntDomain {
    var lower = expression.constant
    var upper = expression.constant
    for ((variable, coefficient) in expression.coeffs) {
        val domain = domains[variable]
        if (coefficient >= 0) {
            lower += coefficient * domain.min
            upper += coefficient * domain.max
        } else {
            lower += coefficient * domain.max
            upper += coefficient * domain.min
        }
    }
    return IntDomain(lower, upper)
}

internal inline fun productBounds(
    left: IntDomain,
    right: IntDomain,
    multiply: (Long, Long) -> Long,
): IntDomain {
    val ll = multiply(left.min, right.min)
    val lu = multiply(left.min, right.max)
    val ul = multiply(left.max, right.min)
    val uu = multiply(left.max, right.max)
    return IntDomain(minOf(minOf(ll, lu), minOf(ul, uu)), maxOf(maxOf(ll, lu), maxOf(ul, uu)))
}
