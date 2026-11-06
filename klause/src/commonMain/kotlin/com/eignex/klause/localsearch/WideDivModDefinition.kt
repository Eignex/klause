package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.DivModExpression
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.factor.arithmetic.integerDivMod
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep.SweepNode
import com.eignex.klause.solver.Assignment
import com.eignex.klause.util.Cancellation
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

internal class WideDivModDefinition(
    private val relation: WideDivMod,
    override val out: Int,
    private val quotient: Boolean,
    private val expression: DivModExpression,
    private val weight: BigInt,
    private val radix: BigInt?,
) : SweepNode {
    override val outIsBool: Boolean get() = false
    override val boolInputs: IntArray get() = emptyIntArray()
    override val intInputs: IntArray = relation.dividend.coefficients.keys.toIntArray()

    override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
        val outputs = integerDivMod(relation.dividend.evaluate(assignment::intValue), relation.divisor,
            relation.convention) ?: return SweepNode.NO_WRITE
        val value = (if (quotient) outputs.first else outputs.second) - expression.constant
        var digit = checkNotNull(integerDivMod(value, weight, relation.convention)).first
        if (radix != null) digit = checkNotNull(integerDivMod(digit, radix, relation.convention)).second
        val domain = domains[out]
        return domain.clamp(when {
            digit < bigIntOf(domain.min) -> domain.min
            digit > bigIntOf(domain.max) -> domain.max
            else -> digit.toLongExact()
        })
    }
}

internal fun wideDivModDefinitions(
    problem: Problem,
    cancellation: Cancellation = Cancellation.Never,
): List<SweepNode> {
    val definitions = ArrayList<SweepNode>()
    for (factor in problem.factors) {
        cancellation.charge(1)
        if (cancellation()) return emptyList()
        val relation = factor as? WideDivMod ?: continue
        for ((expression, quotient) in listOf(relation.quotient to true, relation.remainder to false)) {
            val count = expression.coefficients.size.toLong()
            cancellation.charge(count * (1L + count.toString(2).length))
            if (cancellation()) return emptyList()
            val weights = expression.coefficients.entries.sortedWith { left, right ->
                left.value.compareTo(right.value)
            }
            if (weights.firstOrNull()?.value != BIG_ONE) continue
            val canonical = (0 until weights.lastIndex).all { i ->
                cancellation.charge(1)
                if (cancellation()) return emptyList()
                val (variable, weight) = weights[i]
                val next = weights[i + 1].value
                val radix = next / weight
                radix > BIG_ONE && radix * weight == next &&
                    problem.intBounds.hasLower(variable) && problem.intBounds.hasUpper(variable) &&
                    problem.intBounds.lower(variable) >= 0L && bigIntOf(problem.intBounds.upper(variable)) < radix
            }
            if (!canonical) continue
            for (i in weights.indices) {
                cancellation.charge(1)
                if (cancellation()) return emptyList()
                val (output, weight) = weights[i]
                val next = weights.getOrNull(i + 1)?.value
                val radix = next?.div(weight)
                val validRadix = radix == null || (radix > BIG_ONE && radix * weight == next)
                if (!validRadix || output in relation.dividend.coefficients ||
                    (output in relation.quotient.coefficients && output in relation.remainder.coefficients) ||
                    !problem.intBounds.hasLower(output) || problem.intBounds.lower(output) == Long.MIN_VALUE) continue
                definitions += WideDivModDefinition(relation, output, quotient, expression, weight, radix)
            }
        }
    }
    return if (cancellation()) emptyList() else definitions
}
