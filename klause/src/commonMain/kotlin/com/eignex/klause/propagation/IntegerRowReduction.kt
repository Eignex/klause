package com.eignex.klause.propagation

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.ceilDiv
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.minOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.rem
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

internal fun IntegerConflictRow.reduced(
    state: PropagationState,
    divisor: BigInt = BIG_ONE,
    budget: ArithmeticLearningBudget? = null,
): IntegerConflictRow? {
    require(divisor > BIG_ZERO)
    budget?.spend(bytes = variables.size.toLong() * 64)
    val origins = LongArray(variables.size)
    var degree = -bound
    for (index in variables.indices) {
        if (budget == null) state.checkFiniteWork() else budget.spend()
        // Only declared domains are unconditional premises; live branch bounds cannot enter a retained cut.
        val declared = state.problem.intDomainOrNull(variables[index]) ?: return null
        val coefficient = coefficients[index]
        val origin = if (coefficient > BIG_ZERO) declared.max else declared.min
        origins[index] = origin
        budget?.scalar(coefficient, bigIntOf(origin))
        val contribution = coefficient * bigIntOf(origin)
        budget?.scalar(degree, contribution)
        degree += contribution
    }
    if (degree <= BIG_ZERO) return null
    budget?.scalar(degree, divisor)
    val remainder = degree % divisor
    val quotient = ceilDiv(degree, divisor)
    budget?.scalar(remainder, quotient)
    val nextDegree = if (remainder == BIG_ZERO) degree else remainder * quotient
    var nextBound = -nextDegree
    val nextCoefficients = Array(variables.size) { index ->
        val coefficient = coefficients[index]
        budget?.scalar(coefficient, divisor)
        val weight = minOf(coefficient.abs(), degree)
        // Scaled MIR for nonnegative integer distances from the declared box endpoint.
        val reduced = if (remainder == BIG_ZERO) weight else
            remainder * (weight / divisor) + minOf(weight % divisor, remainder)
        val signed = if (coefficient > BIG_ZERO) reduced else -reduced
        budget?.scalar(signed, bigIntOf(origins[index]))
        val contribution = signed * bigIntOf(origins[index])
        budget?.scalar(nextBound, contribution)
        nextBound += contribution
        signed
    }
    return IntegerConflictRow.source(variables, nextCoefficients, nextBound, useLo = true, guard, budget)
}
