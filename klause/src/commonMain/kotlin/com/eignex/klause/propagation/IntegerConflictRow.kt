package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.ceilDiv
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.floorDiv
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

internal class IntegerConflictRow private constructor(
    val variables: IntArray,
    val coefficients: Array<BigInt>,
    val bound: BigInt,
    val guard: Int = Lit.NONE,
) {
    val directionHash: Int = 31 * variables.contentHashCode() + coefficients.contentHashCode()

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, 2L * Int.SIZE_BYTES, protected)
        sample.ownedReferences(coefficients, coefficients.size.toLong(), protected)
        for (value in coefficients) {
            sample.owned(value, 32L + (value.magnitudeBitLength().toLong() + 7) / 8, protected)
        }
        sample.owned(bound, 32L + (bound.magnitudeBitLength().toLong() + 7) / 8, protected)
    }

    fun coefficient(variable: Int): BigInt {
        val index = variables.binarySearchInt(variable)
        return if (index < 0) BIG_ZERO else coefficients[index]
    }

    fun subsumes(other: IntegerConflictRow): Boolean =
        directionHash == other.directionHash &&
            (guard == Lit.NONE || guard == other.guard) && bound <= other.bound &&
            variables.contentEquals(other.variables) && coefficients.contentEquals(other.coefficients)

    fun minimum(state: PropagationState, trail: Int? = null, budget: ArithmeticLearningBudget? = null): BigInt =
        activity(state, trail, maximum = false, budget)

    fun maximum(state: PropagationState): BigInt = activity(state, null, maximum = true, null)

    private fun activity(state: PropagationState, trail: Int?, maximum: Boolean,
        budget: ArithmeticLearningBudget?): BigInt {
        var sum = BIG_ZERO
        for (i in variables.indices) {
            visit(state, budget)
            val domain = domain(state, variables[i], trail)
            val coefficient = coefficients[i]
            val endpoint = if ((coefficient > BIG_ZERO) != maximum) domain.min else domain.max
            scalar(state, budget, coefficient, bigIntOf(endpoint))
            val contribution = coefficient * bigIntOf(endpoint)
            scalar(state, budget, sum, contribution)
            sum += contribution
        }
        return sum
    }

    fun boundFor(state: PropagationState, variable: Int, trail: Int, budget: ArithmeticLearningBudget? = null): Long? {
        val pivot = variables.binarySearchInt(variable)
        if (pivot < 0) return null
        var other = BIG_ZERO
        for (i in variables.indices) {
            visit(state, budget)
            if (i == pivot) continue
            val domain = domain(state, variables[i], trail)
            val endpoint = if (coefficients[i] > BIG_ZERO) domain.min else domain.max
            scalar(state, budget, coefficients[i], bigIntOf(endpoint))
            val contribution = coefficients[i] * bigIntOf(endpoint)
            scalar(state, budget, other, contribution)
            other += contribution
        }
        scalar(state, budget, bound, other)
        val next = endpoint(bound - other, coefficients[pivot])
        return if (next.fitsLong()) next.toLongExact() else null
    }

    fun assertsAt(state: PropagationState, trail: Int, budget: ArithmeticLearningBudget? = null): Boolean {
        if (!state.integerGuardTrueAt(guard, trail)) return false
        budget?.spend(bytes = variables.size.toLong() * 48)
        val domains = Array(variables.size) {
            visit(state, budget)
            domain(state, variables[it], trail)
        }
        val contributions = Array(variables.size) { BIG_ZERO }
        var minimum = BIG_ZERO
        for (index in variables.indices) {
            visit(state, budget)
            val value = if (coefficients[index] > BIG_ZERO) domains[index].min else domains[index].max
            scalar(state, budget, coefficients[index], bigIntOf(value))
            val contribution = coefficients[index] * bigIntOf(value)
            contributions[index] = contribution
            scalar(state, budget, minimum, contribution)
            minimum += contribution
        }
        if (minimum > bound) return false
        for (index in variables.indices) {
            visit(state, budget)
            val coefficient = coefficients[index]
            scalar(state, budget, bound, minimum)
            val next = endpoint(bound - (minimum - contributions[index]), coefficient)
            val domain = domains[index]
            val tightens = if (coefficient > BIG_ZERO) next < bigIntOf(domain.max) else next > bigIntOf(domain.min)
            // Admission certifies a raw bound assertion, without relying on sparse snapping.
            if (tightens && next.fitsLong() &&
                state.inDomainAt(variables[index], next.toLongExact(), trail, domain)) return true
        }
        return false
    }

    fun resolve(other: IntegerConflictRow, variable: Int,
        budget: ArithmeticLearningBudget? = null): IntegerConflictRow? {
        if (guard != Lit.NONE && other.guard != Lit.NONE && guard != other.guard) return null
        val a = coefficient(variable)
        val b = other.coefficient(variable)
        if (a == BIG_ZERO || b == BIG_ZERO || (a > BIG_ZERO) == (b > BIG_ZERO)) return null
        budget?.scalar(a, b)
        val divisor = a.abs().gcd(b.abs())
        val left = b.abs() / divisor
        val right = a.abs() / divisor
        budget?.spend(bytes = (variables.size.toLong() + other.variables.size) * 80)
        val terms = mutableMapOf<Int, BigInt>()
        for (i in variables.indices) {
            budget?.scalar(coefficients[i], left)
            terms[variables[i]] = coefficients[i] * left
        }
        for (i in other.variables.indices) {
            budget?.scalar(other.coefficients[i], right)
            val v = other.variables[i]
            val contribution = other.coefficients[i] * right
            budget?.scalar(terms[v] ?: BIG_ZERO, contribution)
            val next = (terms[v] ?: BIG_ZERO) + contribution
            if (next == BIG_ZERO) terms.remove(v) else terms[v] = next
        }
        budget?.scalar(bound, left)
        budget?.scalar(other.bound, right)
        return normalized(terms, bound * left + other.bound * right,
            if (guard != Lit.NONE) guard else other.guard, budget)
    }

    companion object {
        fun source(factor: LinearPropagator, useLo: Boolean, guard: Int = Lit.NONE,
            budget: ArithmeticLearningBudget? = null): IntegerConflictRow? {
            budget?.spend(bytes = factor.vars.size.toLong() * 40)
            val coefficients = Array(factor.coeffs.size) {
                budget?.spend()
                bigIntOf(factor.coeffs[it])
            }
            return source(factor.vars, coefficients, bigIntOf(factor.bound), useLo, guard, budget)
        }

        fun source(variables: IntArray, coefficients: Array<BigInt>, bound: BigInt, useLo: Boolean,
            guard: Int = Lit.NONE, budget: ArithmeticLearningBudget? = null): IntegerConflictRow? {
            budget?.spend(bytes = variables.size.toLong() * 80)
            val terms = mutableMapOf<Int, BigInt>()
            for (i in variables.indices) {
                budget?.scalar(terms[variables[i]] ?: BIG_ZERO, coefficients[i])
                val coefficient = if (useLo) coefficients[i] else -coefficients[i]
                val variable = variables[i]
                val next = (terms[variable] ?: BIG_ZERO) + coefficient
                if (next == BIG_ZERO) terms.remove(variable) else terms[variable] = next
            }
            return normalized(terms, if (useLo) bound else -bound, guard, budget)
        }

        private fun normalized(terms: Map<Int, BigInt>, bound: BigInt, guard: Int,
            budget: ArithmeticLearningBudget?): IntegerConflictRow? {
            if (terms.isEmpty()) return null
            var divisor = BIG_ZERO
            for (coefficient in terms.values) {
                budget?.scalar(divisor, coefficient)
                divisor = divisor.gcd(coefficient.abs())
            }
            val height = Int.SIZE_BITS - terms.size.countLeadingZeroBits()
            budget?.spend(work = terms.size.toLong() * height, bytes = terms.size.toLong() * 16)
            val variables = terms.keys.toIntArray()
            variables.sort()
            val coefficients = Array(variables.size) {
                val coefficient = terms.getValue(variables[it])
                budget?.scalar(coefficient, divisor)
                coefficient / divisor
            }
            budget?.scalar(bound, divisor)
            return IntegerConflictRow(variables, coefficients, floorDiv(bound, divisor), guard)
        }

        internal fun endpoint(residual: BigInt, coefficient: BigInt): BigInt =
            if (coefficient > BIG_ZERO) floorDiv(residual, coefficient) else ceilDiv(residual, coefficient)

        private fun domain(state: PropagationState, variable: Int, trail: Int?): IntDomain =
            if (trail == null) state.intDomains[variable] else state.domainAt(variable, trail)

        private fun scalar(state: PropagationState, budget: ArithmeticLearningBudget?, left: BigInt, right: BigInt) {
            if (budget == null) state.checkIntegerScalarWork(left, right) else budget.scalar(left, right)
        }

        private fun visit(state: PropagationState, budget: ArithmeticLearningBudget?) {
            if (budget == null) state.checkFiniteWork() else budget.spend()
        }
    }
}
