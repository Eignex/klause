package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.internals.ceilDivLong
import com.eignex.klause.factor.arithmetic.internals.floorDivLong
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.arrays.binarySearchInt
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.addExactOrNull
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.ceilDiv
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.floorDiv
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.mulExactOrNull
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.subExactOrNull
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
        budget: ArithmeticLearningBudget?, excluded: Int = -1): BigInt {
        var narrow = 0L
        var wide: BigInt? = null
        for (i in variables.indices) {
            visit(state, budget)
            if (i == excluded) continue
            val domain = domain(state, variables[i], trail)
            val coefficient = coefficients[i]
            val endpoint = if ((coefficient > BIG_ZERO) != maximum) domain.min else domain.max
            if (wide == null && coefficient.fitsLong()) {
                state.checkIntegerWordWork(budget)
                val contribution = mulExactOrNull(coefficient.toLongExact(), endpoint)
                val next = contribution?.let { addExactOrNull(narrow, it) }
                if (next != null) {
                    narrow = next
                    continue
                }
            }
            val sum = wide ?: bigIntOf(narrow)
            scalar(state, budget, coefficient, bigIntOf(endpoint))
            val contribution = coefficient * bigIntOf(endpoint)
            scalar(state, budget, sum, contribution)
            wide = sum + contribution
        }
        if (wide == null) budget?.spend(bytes = 48)
        return wide ?: bigIntOf(narrow)
    }

    fun boundFor(state: PropagationState, variable: Int, trail: Int, budget: ArithmeticLearningBudget? = null): Long? {
        val pivot = variables.binarySearchInt(variable)
        if (pivot < 0) return null
        val other = activity(state, trail, maximum = false, budget, excluded = pivot)
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
        narrowAssertion(state, trail, domains, budget)?.let { return it }
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

    private fun narrowAssertion(state: PropagationState, trail: Int, domains: Array<IntDomain>,
        budget: ArithmeticLearningBudget?): Boolean? {
        if (!bound.fitsLong()) return null
        budget?.spend(bytes = variables.size.toLong() * Long.SIZE_BYTES)
        val contributions = LongArray(variables.size)
        var minimum = 0L
        for (index in variables.indices) {
            visit(state, budget)
            val coefficient = coefficients[index]
            if (!coefficient.fitsLong()) return null
            val value = if (coefficient > BIG_ZERO) domains[index].min else domains[index].max
            state.checkIntegerWordWork(budget)
            val contribution = mulExactOrNull(coefficient.toLongExact(), value) ?: return null
            minimum = addExactOrNull(minimum, contribution) ?: return null
            contributions[index] = contribution
        }
        val upper = bound.toLongExact()
        if (minimum > upper) return false
        for (index in variables.indices) {
            visit(state, budget)
            state.checkIntegerWordWork(budget)
            val other = subExactOrNull(minimum, contributions[index]) ?: return null
            val residual = subExactOrNull(upper, other) ?: return null
            val coefficient = coefficients[index].toLongExact()
            if (residual == Long.MIN_VALUE && coefficient == -1L) return null
            visit(state, budget)
            val next = if (coefficient > 0) floorDivLong(residual, coefficient) else ceilDivLong(residual, coefficient)
            val domain = domains[index]
            val tightens = if (coefficient > 0) next < domain.max else next > domain.min
            if (tightens && state.inDomainAt(variables[index], next, trail, domain)) return true
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
        val aMagnitude = a.abs()
        val bMagnitude = b.abs()
        val divisor = aMagnitude.gcd(bMagnitude)
        val left = if (bMagnitude == divisor) BIG_ONE else bMagnitude / divisor
        val right = if (aMagnitude == divisor) BIG_ONE else aMagnitude / divisor
        val required = variables.size.toLong() + other.variables.size
        budget?.spend(bytes = required * 16)
        if (required > Int.MAX_VALUE) return null
        val capacity = required.toInt()
        val mergedVariables = IntArray(capacity)
        val mergedCoefficients = Array(capacity) { BIG_ZERO }
        var first = 0
        var second = 0
        var size = 0
        while (first < variables.size || second < other.variables.size) {
            budget?.spend()
            val firstVariable = variables.getOrElse(first) { Int.MAX_VALUE }
            val secondVariable = other.variables.getOrElse(second) { Int.MAX_VALUE }
            val nextVariable = minOf(firstVariable, secondVariable)
            var coefficient = BIG_ZERO
            if (first < variables.size && firstVariable == nextVariable) {
                budget?.scalar(coefficients[first], left)
                coefficient = if (left == BIG_ONE) coefficients[first] else coefficients[first] * left
                first++
            }
            if (second < other.variables.size && secondVariable == nextVariable) {
                budget?.scalar(other.coefficients[second], right)
                val contribution = if (right == BIG_ONE) {
                    other.coefficients[second]
                } else {
                    other.coefficients[second] * right
                }
                budget?.scalar(coefficient, contribution)
                coefficient += contribution
                second++
            }
            if (coefficient != BIG_ZERO) {
                mergedVariables[size] = nextVariable
                mergedCoefficients[size++] = coefficient
            }
        }
        budget?.scalar(bound, left)
        budget?.scalar(other.bound, right)
        val firstBound = if (left == BIG_ONE) bound else bound * left
        val secondBound = if (right == BIG_ONE) other.bound else other.bound * right
        return normalizedSorted(mergedVariables, mergedCoefficients, size, firstBound + secondBound,
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
            var ordered = true
            for (index in variables.indices) {
                budget?.spend()
                if (coefficients[index] == BIG_ZERO || (index > 0 && variables[index - 1] >= variables[index])) {
                    ordered = false
                    break
                }
            }
            if (ordered && variables.isNotEmpty()) {
                if (useLo) return normalizedSorted(variables, coefficients, variables.size, bound, guard, budget)
                budget?.spend(bytes = variables.size.toLong() * 16 + 64)
                val reversed = Array(variables.size) {
                    budget?.scalar(coefficients[it])
                    -coefficients[it]
                }
                return normalizedSorted(variables, reversed, variables.size, -bound, guard, budget)
            }
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
                if (divisor == BIG_ONE) break
            }
            val height = Int.SIZE_BITS - terms.size.countLeadingZeroBits()
            budget?.spend(work = terms.size.toLong() * height, bytes = terms.size.toLong() * 16)
            val variables = terms.keys.toIntArray()
            variables.sort()
            val coefficients = Array(variables.size) {
                val coefficient = terms.getValue(variables[it])
                if (divisor == BIG_ONE) {
                    budget?.scalar(coefficient, BIG_ONE)
                    coefficient
                } else {
                    budget?.scalar(coefficient, divisor)
                    coefficient / divisor
                }
            }
            if (divisor == BIG_ONE) return IntegerConflictRow(variables, coefficients, bound, guard)
            budget?.scalar(bound, divisor)
            return IntegerConflictRow(variables, coefficients, floorDiv(bound, divisor), guard)
        }

        private fun normalizedSorted(variables: IntArray, coefficients: Array<BigInt>, size: Int,
            bound: BigInt, guard: Int, budget: ArithmeticLearningBudget?): IntegerConflictRow? {
            if (size == 0) return null
            var divisor = BIG_ZERO
            for (index in 0 until size) {
                budget?.scalar(divisor, coefficients[index])
                divisor = divisor.gcd(coefficients[index].abs())
                if (divisor == BIG_ONE) break
            }
            budget?.spend(work = size.toLong(), bytes = size.toLong() * 16)
            if (divisor == BIG_ONE) {
                for (index in 0 until size) budget?.scalar(coefficients[index], BIG_ONE)
                return IntegerConflictRow(variables.copyOf(size), coefficients.copyOfRange(0, size), bound, guard)
            }
            val normalized = Array(size) {
                budget?.scalar(coefficients[it], divisor)
                coefficients[it] / divisor
            }
            budget?.scalar(bound, divisor)
            return IntegerConflictRow(variables.copyOf(size), normalized, floorDiv(bound, divisor), guard)
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
