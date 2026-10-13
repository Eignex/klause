package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic

internal class PolynomialProjection private constructor(private val levels: Array<List<Polynomial>>) {
    val variableCount: Int get() = levels.size
    val polynomialCount: Long get() = levels.sumOf { it.size.toLong() }

    fun polynomials(variable: Int): List<Polynomial> = levels[variable].toList()

    companion object {
        fun compute(
            polynomials: List<Polynomial>,
            variableCount: Int,
            arithmetic: PolynomialArithmetic,
        ): PolynomialProjection {
            val pending = prepare(polynomials, variableCount, arithmetic)
            while (true) pending.next()?.let { return it }
        }

        fun prepare(
            polynomials: List<Polynomial>,
            variableCount: Int,
            arithmetic: PolynomialArithmetic,
        ): Iterator<PolynomialProjection?> = sequence {
            require(variableCount >= 0)
            arithmetic.step()
            for (polynomial in polynomials) {
                arithmetic.step()
                require(polynomial.variables.all { it < variableCount })
            }
            val levels = Array(variableCount) {
                arithmetic.step()
                emptyList<Polynomial>()
            }
            var pending = projectionFamily(polynomials, arithmetic)
            for (variable in variableCount - 1 downTo 0) {
                arithmetic.step()
                val top = mutableListOf<Polynomial>()
                val lower = mutableListOf<Polynomial>()
                for (polynomial in pending) {
                    arithmetic.step()
                    if (polynomial.mainVariable == variable) top.add(polynomial) else lower.add(polynomial)
                }
                levels[variable] = top.toList()
                if (variable > 0) {
                    val eliminated = eliminate(top, variable, arithmetic)
                    var projected: List<Polynomial>? = null
                    while (projected == null) {
                        projected = eliminated.next()
                        if (projected == null) yield(null)
                    }
                    pending = projectionFamily(lower + projected, arithmetic)
                }
            }
            arithmetic.step()
            yield(PolynomialProjection(levels))
        }.iterator()
    }
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
): Iterator<List<Polynomial>?> = sequence {
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
            val pending = arithmetic.preparePrincipalSubresultants(
                reductum, arithmetic.derivative(reductum, variable), variable,
            )
            var result: List<Polynomial>? = null
            while (result == null) {
                result = pending.next()
                if (result == null) yield(null)
            }
            projected.addAll(result)
            yield(null)
        }
    }
    for (left in families.indices) {
        for (right in left + 1 until families.size) {
            for (a in families[left]) {
                for (b in families[right]) {
                    arithmetic.step()
                    val pending = arithmetic.preparePrincipalSubresultants(a, b, variable)
                    var result: List<Polynomial>? = null
                    while (result == null) {
                        result = pending.next()
                        if (result == null) yield(null)
                    }
                    projected.addAll(result)
                    yield(null)
                }
            }
        }
    }
    yield(projected)
}.iterator()

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
