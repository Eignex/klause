package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic

internal class PolynomialProjection private constructor(
    private val levels: Array<List<Polynomial>>,
    private val liftingOrder: List<Int>?,
) {
    val variableCount: Int get() = levels.size
    val polynomialCount: Long get() = levels.sumOf { it.size.toLong() }

    fun polynomials(variable: Int): List<Polynomial> = levels[variable].toList()
    fun liftingVariable(level: Int): Int {
        require(level in levels.indices)
        return liftingOrder?.get(level) ?: level
    }

    companion object {
        fun compute(
            polynomials: List<Polynomial>,
            variableCount: Int,
            arithmetic: PolynomialArithmetic,
            liftingOrder: List<Int>? = null,
        ): PolynomialProjection {
            require(variableCount >= 0)
            arithmetic.step()
            val order = liftingOrder?.let { supplied ->
                require(supplied.size == variableCount)
                val seen = BooleanArray(variableCount) { arithmetic.step(); false }
                supplied.map { variable ->
                    arithmetic.step()
                    require(variable in 0 until variableCount && !seen[variable])
                    seen[variable] = true
                    variable
                }
            }
            for (polynomial in polynomials) {
                arithmetic.step()
                require(polynomial.variables.all { it < variableCount })
            }
            val levels = Array(variableCount) {
                arithmetic.step()
                emptyList<Polynomial>()
            }
            var pending = projectionFamily(polynomials, arithmetic)
            for (level in variableCount - 1 downTo 0) {
                arithmetic.step()
                val variable = order?.get(level) ?: level
                val top = mutableListOf<Polynomial>()
                val lower = mutableListOf<Polynomial>()
                for (polynomial in pending) {
                    arithmetic.step()
                    val contains = if (order == null) polynomial.mainVariable == variable else {
                        repeat(polynomial.termCount) { arithmetic.step() }
                        arithmetic.step()
                        polynomial.degree(variable) > 0
                    }
                    if (contains) top.add(polynomial) else lower.add(polynomial)
                }
                levels[variable] = top.toList()
                if (level > 0) {
                    pending = projectionFamily(lower + eliminate(top, variable, arithmetic), arithmetic)
                }
            }
            arithmetic.step()
            return PolynomialProjection(levels, order)
        }
    }
}

private const val MAX_ORDER_VARIABLES = 32
private const val MAX_ORDER_POLYNOMIALS = 128
private const val MAX_ORDER_TERMS = 2048

internal fun satisfactionLiftingOrder(
    polynomials: List<Polynomial>,
    variableCount: Int,
    arithmetic: PolynomialArithmetic,
): List<Int>? {
    require(variableCount >= 0)
    arithmetic.step()
    if (variableCount !in 2..MAX_ORDER_VARIABLES || polynomials.size > MAX_ORDER_POLYNOMIALS) return null
    var termCount = 0
    for (polynomial in polynomials) {
        arithmetic.step()
        if (polynomial.termCount > MAX_ORDER_TERMS - termCount) return null
        termCount += polynomial.termCount
    }
    repeat(variableCount * 3) { arithmetic.step() }
    val degrees = IntArray(variableCount)
    val totalDegrees = LongArray(variableCount)
    val incidences = IntArray(variableCount)
    for (polynomial in polynomials) {
        // Bound sparse materialization before requesting its sorted term snapshot.
        repeat(polynomial.termCount * (variableCount + 1) + 1) { arithmetic.step() }
        for (term in polynomial.terms) {
            var totalDegree = 0L
            for (variable in 0 until variableCount) {
                arithmetic.step()
                totalDegree += term.monomial.exponent(variable).toLong()
            }
            for (variable in 0 until variableCount) {
                arithmetic.step()
                val degree = term.monomial.exponent(variable)
                if (degree == 0) continue
                degrees[variable] = maxOf(degrees[variable], degree)
                totalDegrees[variable] = maxOf(totalDegrees[variable], totalDegree)
                incidences[variable]++
            }
        }
    }
    val order = List(variableCount) { arithmetic.step(); it }
    return order.sortedWith(Comparator { left, right ->
        arithmetic.step()
        // Eliminate lower-degree, less incident coordinates first; ties retain source order.
        val degree = degrees[right].compareTo(degrees[left])
        val totalDegree = totalDegrees[right].compareTo(totalDegrees[left])
        val incidence = incidences[right].compareTo(incidences[left])
        when {
            degree != 0 -> degree
            totalDegree != 0 -> totalDegree
            incidence != 0 -> incidence
            else -> left.compareTo(right)
        }
    })
}

private fun projectionFamily(values: List<Polynomial>, arithmetic: PolynomialArithmetic): List<Polynomial> {
    val distinct = LinkedHashSet<Polynomial>()
    for (polynomial in values) {
        arithmetic.step()
        if (polynomial.mainVariable == null) continue
        distinct.add(arithmetic.scale(polynomial, polynomial.terms.last().coefficient.reciprocal()))
    }
    return distinct.toList()
}

private fun eliminate(
    polynomials: List<Polynomial>,
    variable: Int,
    arithmetic: PolynomialArithmetic,
): List<Polynomial> {
    val projected = mutableListOf<Polynomial>()
    val families = mutableListOf<List<Polynomial>>()
    for (polynomial in polynomials) {
        arithmetic.step()
        val coefficients = arithmetic.coefficients(polynomial, variable)
        projected.addAll(coefficients)
        val reducta = reducta(polynomial, coefficients, variable, arithmetic)
        families.add(reducta)
        for (reductum in reducta) {
            arithmetic.step()
            projected.addAll(
                arithmetic.principalSubresultants(reductum, arithmetic.derivative(reductum, variable), variable),
            )
        }
    }
    for (left in families.indices) {
        for (right in left + 1 until families.size) {
            for (a in families[left]) {
                for (b in families[right]) {
                    arithmetic.step()
                    projected.addAll(arithmetic.principalSubresultants(a, b, variable))
                }
            }
        }
    }
    return projected
}

private fun reducta(
    polynomial: Polynomial,
    coefficients: List<Polynomial>,
    variable: Int,
    arithmetic: PolynomialArithmetic,
): List<Polynomial> {
    val result = mutableListOf<Polynomial>()
    var remaining = polynomial
    for (degree in coefficients.lastIndex downTo 1) {
        arithmetic.step()
        val coefficient = coefficients[degree]
        if (coefficient.isZero) continue
        result.add(remaining)
        val leading = arithmetic.multiply(coefficient, arithmetic.power(Polynomial.variable(variable), degree))
        remaining = arithmetic.subtract(remaining, leading)
    }
    return result
}
