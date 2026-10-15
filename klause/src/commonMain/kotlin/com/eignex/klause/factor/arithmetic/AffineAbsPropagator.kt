package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.ceilDiv
import com.eignex.klause.factor.arithmetic.internals.floorDiv
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal class AffineAbsPropagator(private val factor: AffineAbs) : Propagator {
    private val lowerRows = factor.linearForm.rows.map { it as Linear }
    private val positive = factor.result.comparedTo(factor.operand, BIG_ONE, LinearOp.EQ)
    private val negative = factor.result.comparedTo(factor.operand, -BIG_ONE, LinearOp.EQ)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (row in lowerRows) if (!enforce(row, state)) return false
        val (lo, hi) = range(factor.operand, state)
        val magnitude = maxOf(lo.abs(), hi.abs())
        val upper = factor.result.comparedTo(AbsAffineValue(emptyMap(), magnitude), BIG_ONE, LinearOp.LE)
        if (!enforce(upper, state)) return false
        val positivePossible = possible(positive, state)
        val negativePossible = possible(negative, state)
        if (!positivePossible && !negativePossible) return false
        return when {
            lo >= BIG_ZERO || !negativePossible -> enforce(positive, state)
            hi <= BIG_ZERO || !positivePossible -> enforce(negative, state)
            else -> true
        }
    }

    private fun range(value: AbsAffineValue, state: PropagationState): Pair<BigInt, BigInt> {
        var lower = value.constant
        var upper = value.constant
        for ((variable, coefficient) in value.coefficients) {
            val domain = state.intDomains[variable]
            val first = coefficient * bigIntOf(domain.min)
            val last = coefficient * bigIntOf(domain.max)
            lower += minOf(first, last)
            upper += maxOf(first, last)
        }
        return lower to upper
    }

    private fun possible(row: Linear, state: PropagationState): Boolean {
        val constants = checkNotNull(row.integralConstants)
        val value = AbsAffineValue(row.vars.indices.associate { row.vars[it] to constants.exactCoeff(it) },
            -constants.exactBound)
        val (lo, hi) = range(value, state)
        return lo <= BIG_ZERO && hi >= BIG_ZERO
    }

    // Selected signs are premises of these deductions; absent clause reasons retain chronological fallback.
    private fun enforce(row: Linear, state: PropagationState): Boolean {
        val constants = checkNotNull(row.integralConstants)
        val coefficients = Array(row.vars.size) { constants.exactCoeff(it) }
        val lower = Array(row.vars.size) { index ->
            val domain = state.intDomains[row.vars[index]]
            minOf(coefficients[index] * bigIntOf(domain.min), coefficients[index] * bigIntOf(domain.max))
        }
        val upper = Array(row.vars.size) { index ->
            val domain = state.intDomains[row.vars[index]]
            maxOf(coefficients[index] * bigIntOf(domain.min), coefficients[index] * bigIntOf(domain.max))
        }
        var totalLower = BIG_ZERO
        var totalUpper = BIG_ZERO
        for (index in coefficients.indices) {
            totalLower += lower[index]
            totalUpper += upper[index]
        }
        val bound = constants.exactBound
        if (totalLower > bound || (row.op == LinearOp.EQ && totalUpper < bound)) return false
        for (index in coefficients.indices) {
            val coefficient = coefficients[index]
            val variable = row.vars[index]
            val rhsUpper = bound - (totalLower - lower[index])
            if (coefficient > BIG_ZERO) {
                if (!tightenUpper(state, variable, floorDiv(rhsUpper, coefficient))) return false
            } else if (!tightenLower(state, variable, ceilDiv(rhsUpper, coefficient))) return false
            if (row.op == LinearOp.EQ) {
                val rhsLower = bound - (totalUpper - upper[index])
                if (coefficient > BIG_ZERO) {
                    if (!tightenLower(state, variable, ceilDiv(rhsLower, coefficient))) return false
                } else if (!tightenUpper(state, variable, floorDiv(rhsLower, coefficient))) return false
            }
        }
        return true
    }

    private fun tightenLower(state: PropagationState, variable: Int, value: BigInt): Boolean = when {
        value > LONG_MAX -> false
        value <= LONG_MIN -> true
        else -> state.tightenIntMin(variable, value.toLongExact())
    }

    private fun tightenUpper(state: PropagationState, variable: Int, value: BigInt): Boolean = when {
        value < LONG_MIN -> false
        value >= LONG_MAX -> true
        else -> state.tightenIntMax(variable, value.toLongExact())
    }

    private companion object {
        val LONG_MIN = bigIntOf(Long.MIN_VALUE)
        val LONG_MAX = bigIntOf(Long.MAX_VALUE)
    }
}
