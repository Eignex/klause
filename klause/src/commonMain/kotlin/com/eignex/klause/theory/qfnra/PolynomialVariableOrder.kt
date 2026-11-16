package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic

internal fun polynomialVariableOrder(
    polynomials: List<Polynomial>,
    variableCount: Int,
    arithmetic: PolynomialArithmetic,
): IntArray {
    require(variableCount >= 0)
    val degrees = LongArray(variableCount) {
        arithmetic.step()
        0L
    }
    val maximum = IntArray(variableCount) {
        arithmetic.step()
        0
    }
    val occurrences = LongArray(variableCount) {
        arithmetic.step()
        0L
    }
    for (polynomial in polynomials) {
        arithmetic.step()
        val local = HashMap<Int, Int>()
        for (term in polynomial.terms) for (variable in term.monomial.variables) {
            arithmetic.step()
            require(variable in 0 until variableCount)
            val degree = term.monomial.exponent(variable)
            local[variable] = maxOf(local[variable] ?: 0, degree)
            if (occurrences[variable] < Long.MAX_VALUE) occurrences[variable]++
        }
        for ((variable, degree) in local) {
            arithmetic.step()
            degrees[variable] += minOf(degree.toLong(), Long.MAX_VALUE - degrees[variable])
            maximum[variable] = maxOf(maximum[variable], degree)
        }
    }
    val order = (0 until variableCount).toMutableList()
    // Construction eliminates from the end; smaller boundary degrees precede larger families.
    order.sortWith { left, right ->
        arithmetic.step()
        val degree = degrees[right].compareTo(degrees[left])
        val peak = maximum[right].compareTo(maximum[left])
        val terms = occurrences[right].compareTo(occurrences[left])
        when {
            degree != 0 -> degree
            peak != 0 -> peak
            terms != 0 -> terms
            else -> left.compareTo(right)
        }
    }
    return order.toIntArray()
}
