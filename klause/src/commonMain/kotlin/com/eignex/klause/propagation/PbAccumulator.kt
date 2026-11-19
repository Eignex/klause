package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.ceilDiv
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.gcd
import com.eignex.util.numbers.minOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.rem
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

internal class PbAccumulator {
    private val coefficients = LinkedHashMap<Int, BigInt>()
    var budget: ArithmeticLearningBudget? = null
    private var rhs = BIG_ZERO

    fun forEachVar(action: (Int) -> Unit) {
        for (variable in coefficients.keys) {
            budget?.spend()
            action(variable)
        }
    }

    fun clear() {
        coefficients.clear()
        rhs = BIG_ZERO
    }

    fun isEmpty(): Boolean = coefficients.isEmpty()

    fun hasSafeActivity(): Boolean = positiveDegree() > BIG_ZERO

    private fun put(variable: Int, value: BigInt) {
        if (value == BIG_ZERO) coefficients.remove(variable) else {
            if (!coefficients.containsKey(variable)) budget?.spend(bytes = 64)
            coefficients[variable] = value
        }
    }

    fun positiveDegree(): BigInt {
        var degree = rhs
        for (coefficient in coefficients.values) {
            budget?.scalar(degree, coefficient)
            if (coefficient < BIG_ZERO) degree -= coefficient
        }
        return degree
    }

    fun conflicting(state: PropagationState): Boolean {
        var available = BIG_ZERO
        for ((variable, coefficient) in coefficients) {
            budget?.scalar(available, coefficient)
            val truth = state.boolValues[variable]
            if (truth == true || (truth == null && coefficient > BIG_ZERO)) available += coefficient
        }
        return available < rhs
    }

    fun coefOf(variable: Int): BigInt = coefficients[variable] ?: BIG_ZERO

    fun loadPb(weights: LongArray, literals: IntArray, geBound: Long): Boolean {
        budget?.spend(bytes = weights.size.toLong() * 40)
        return loadPb(Array(weights.size) { bigIntOf(weights[it]) }, literals, bigIntOf(geBound))
    }

    fun loadPb(weights: Array<BigInt>, literals: IntArray, geBound: BigInt): Boolean {
        clear()
        rhs = geBound
        for (index in literals.indices) {
            val literal = literals[index]
            val weight = weights[index]
            val variable = Lit.variable(literal)
            budget?.scalar(coefOf(variable), weight)
            if (Lit.isPositive(literal)) put(variable, coefOf(variable) + weight) else {
                put(variable, coefOf(variable) - weight)
                rhs -= weight
            }
        }
        return true
    }

    fun loadClause(literals: IntArray): Boolean {
        budget?.spend(bytes = literals.size.toLong() * 8)
        return loadPb(Array(literals.size) { BIG_ONE }, literals, BIG_ONE)
    }

    fun addScaled(other: PbAccumulator, mulSelf: Long, mulOther: Long): Boolean =
        addScaled(other, bigIntOf(mulSelf), bigIntOf(mulOther))

    fun addScaled(other: PbAccumulator, mulSelf: BigInt, mulOther: BigInt): Boolean {
        require(mulSelf > BIG_ZERO && mulOther > BIG_ZERO)
        for ((variable, coefficient) in coefficients) {
            budget?.scalar(coefficient, mulSelf)
            coefficients[variable] = coefficient * mulSelf
        }
        budget?.scalar(rhs, mulSelf)
        budget?.scalar(other.rhs, mulOther)
        rhs = rhs * mulSelf + other.rhs * mulOther
        for ((variable, coefficient) in other.coefficients) {
            budget?.scalar(coefficient, mulOther)
            put(variable, coefOf(variable) + coefficient * mulOther)
        }
        return true
    }

    fun guardedBy(guard: Int) {
        if (guard == Lit.NONE) return
        val degree = positiveDegree()
        if (degree <= BIG_ZERO) return
        val variable = Lit.variable(guard)
        budget?.scalar(coefOf(variable), degree)
        // The inactive guard can pay the complete positive degree, independently of the body.
        if (Lit.isPositive(guard)) {
            put(variable, coefOf(variable) - degree)
            rhs -= degree
        } else put(variable, coefOf(variable) + degree)
    }

    fun saturate() {
        val degree = positiveDegree()
        if (degree <= BIG_ZERO) return
        var negatives = BIG_ZERO
        for ((variable, coefficient) in coefficients) {
            budget?.scalar(coefficient, degree)
            val weight = minOf(coefficient.abs(), degree)
            coefficients[variable] = if (coefficient > BIG_ZERO) weight else -weight
            if (coefficient < BIG_ZERO) negatives += weight
        }
        rhs = degree - negatives
    }

    fun normalizeByGcd() {
        var divisor = BIG_ZERO
        for (coefficient in coefficients.values) {
            budget?.scalar(divisor, coefficient)
            divisor = divisor.gcd(coefficient.abs())
        }
        if (divisor <= BIG_ONE) return
        val degree = ceilDiv(positiveDegree(), divisor)
        var negatives = BIG_ZERO
        for ((variable, coefficient) in coefficients) {
            budget?.scalar(coefficient, divisor)
            val next = coefficient / divisor
            coefficients[variable] = next
            if (next < BIG_ZERO) negatives -= next
        }
        rhs = degree - negatives
    }

    fun weakenForDivision(keepVar: Int, divisor: Long, isFalse: (Int) -> Boolean) =
        weakenForDivision(keepVar, bigIntOf(divisor), isFalse)

    fun weakenForDivision(keepVar: Int, divisor: BigInt, isFalse: (Int) -> Boolean) {
        if (divisor <= BIG_ONE) return
        val iterator = coefficients.iterator()
        while (iterator.hasNext()) {
            val (variable, coefficient) = iterator.next()
            budget?.scalar(coefficient, divisor)
            if (variable != keepVar && coefficient.abs() % divisor != BIG_ZERO &&
                !isFalse(Lit.make(variable, coefficient > BIG_ZERO))) {
                if (coefficient > BIG_ZERO) rhs -= coefficient
                iterator.remove()
            }
        }
    }

    fun divideRoundUp(divisor: Long) = divideRoundUp(bigIntOf(divisor))

    fun divideRoundUp(divisor: BigInt) {
        if (divisor <= BIG_ONE) return
        val degree = ceilDiv(positiveDegree(), divisor)
        var negatives = BIG_ZERO
        for ((variable, coefficient) in coefficients) {
            budget?.scalar(coefficient, divisor)
            val weight = ceilDiv(coefficient.abs(), divisor)
            coefficients[variable] = if (coefficient > BIG_ZERO) weight else -weight
            if (coefficient < BIG_ZERO) negatives += weight
        }
        rhs = degree - negatives
    }

    fun materialize(): PbLearned? {
        saturate()
        normalizeByGcd()
        val degree = positiveDegree()
        if (degree <= BIG_ZERO || coefficients.isEmpty()) return null
        budget?.spend(bytes = coefficients.size.toLong() * 12)
        val weights = Array(coefficients.size) { BIG_ZERO }
        val literals = IntArray(coefficients.size)
        var index = 0
        for ((variable, coefficient) in coefficients) {
            budget?.spend()
            weights[index] = coefficient.abs()
            literals[index++] = Lit.make(variable, coefficient > BIG_ZERO)
        }
        return PbLearned(weights, literals, degree)
    }
}

internal class PbLearned(val weights: Array<BigInt>, val literals: IntArray, val degree: BigInt)
