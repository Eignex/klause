package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal class PolynomialInterrupted : RuntimeException()

internal class PolynomialArithmetic(private val cancellation: Cancellation = Cancellation.Never) {
    fun step() {
        if (cancellation()) throw PolynomialInterrupted()
        cancellation.charge(1)
        if (cancellation()) throw PolynomialInterrupted()
    }

    fun add(left: Polynomial, right: Polynomial): Polynomial {
        step()
        return Polynomial.of(left.terms + right.terms)
    }

    fun negate(value: Polynomial): Polynomial = scale(value, BigFraction.MINUS_ONE)

    fun subtract(left: Polynomial, right: Polynomial): Polynomial = add(left, negate(right))

    fun scale(value: Polynomial, coefficient: BigFraction): Polynomial = Polynomial.of(value.terms.map {
        step()
        PolynomialTerm(it.monomial, it.coefficient * coefficient)
    })

    fun multiply(left: Polynomial, right: Polynomial): Polynomial = Polynomial.of(buildList {
        for (a in left.terms) for (b in right.terms) {
            step()
            add(PolynomialTerm(a.monomial.times(b.monomial), a.coefficient * b.coefficient))
        }
    })

    fun power(value: Polynomial, exponent: Int): Polynomial {
        require(exponent >= 0)
        var result = Polynomial.ONE
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            step()
            if (remaining and 1 != 0) result = multiply(result, base)
            remaining = remaining ushr 1
            if (remaining != 0) base = multiply(base, base)
        }
        return result
    }

    fun derivative(value: Polynomial, variable: Int): Polynomial = Polynomial.of(buildList {
        for ((monomial, coefficient) in value.terms) {
            step()
            val degree = monomial.exponent(variable)
            if (degree == 0) continue
            val quotient = checkNotNull(monomial.quotient(Monomial.of(mapOf(variable to 1))))
            add(PolynomialTerm(quotient, coefficient * BigFraction.ofLong(degree.toLong())))
        }
    })

    fun coefficients(value: Polynomial, variable: Int): List<Polynomial> {
        step()
        val groups = List(value.degree(variable) + 1) { mutableListOf<PolynomialTerm>() }
        for ((monomial, coefficient) in value.terms) {
            step()
            groups[monomial.exponent(variable)].add(PolynomialTerm(monomial.without(variable), coefficient))
        }
        return groups.map { Polynomial.of(it) }
    }

    fun substitute(value: Polynomial, variable: Int, replacement: Polynomial): Polynomial {
        var result = Polynomial.ZERO
        for (coefficient in coefficients(value, variable).asReversed()) {
            result = add(multiply(result, replacement), coefficient)
        }
        return result
    }

    fun divideExact(dividend: Polynomial, divisor: Polynomial): Polynomial {
        require(!divisor.isZero)
        val lead = divisor.terms.last()
        var remainder = dividend
        var quotient = Polynomial.ZERO
        while (!remainder.isZero) {
            step()
            val term = remainder.terms.last()
            val monomial = requireNotNull(term.monomial.quotient(lead.monomial)) { "nonexact polynomial division" }
            val next = Polynomial.of(listOf(PolynomialTerm(monomial, term.coefficient * lead.coefficient.reciprocal())))
            quotient = add(quotient, next)
            remainder = subtract(remainder, multiply(next, divisor))
        }
        return quotient
    }

    fun resultant(left: Polynomial, right: Polynomial, variable: Int): Polynomial {
        step()
        if (left.isZero || right.isZero) return Polynomial.ZERO
        val a = coefficients(left, variable)
        val b = coefficients(right, variable)
        val m = a.lastIndex
        val n = b.lastIndex
        if (m == 0) return power(a[0], n)
        if (n == 0) return power(b[0], m)
        require(m <= Int.MAX_VALUE - n) { "resultant dimension overflow" }
        val matrix = Array(m + n) { Array(m + n) { Polynomial.ZERO } }
        for (row in 0 until n) for (column in a.indices) matrix[row][row + column] = a[m - column]
        for (row in 0 until m) for (column in b.indices) matrix[n + row][row + column] = b[n - column]
        return determinant(matrix)
    }

    fun principalSubresultants(left: Polynomial, right: Polynomial, variable: Int): List<Polynomial> {
        step()
        val a = coefficients(left, variable)
        val b = coefficients(right, variable)
        val m = a.lastIndex
        val n = b.lastIndex
        return (0 until minOf(m, n)).map { index ->
            val size = m + n - 2 * index
            val matrix = Array(size) { Array(size) { Polynomial.ZERO } }
            for (row in 0 until n - index) {
                for (column in a.indices) {
                    step()
                    if (row + column < size) matrix[row][row + column] = a[m - column]
                }
            }
            for (row in 0 until m - index) {
                for (column in b.indices) {
                    step()
                    if (row + column < size) matrix[n - index + row][row + column] = b[n - column]
                }
            }
            determinant(matrix)
        }
    }

    private fun determinant(matrix: Array<Array<Polynomial>>): Polynomial {
        var previous = Polynomial.ONE
        var negative = false
        for (column in 0 until matrix.lastIndex) {
            step()
            val pivotRow = (column..matrix.lastIndex).firstOrNull { !matrix[it][column].isZero }
                ?: return Polynomial.ZERO
            if (pivotRow != column) {
                val row = matrix[column]
                matrix[column] = matrix[pivotRow]
                matrix[pivotRow] = row
                negative = !negative
            }
            val pivot = matrix[column][column]
            for (row in column + 1..matrix.lastIndex) {
                for (entry in column + 1..matrix.lastIndex) {
                    val numerator = subtract(
                        multiply(pivot, matrix[row][entry]),
                        multiply(matrix[row][column], matrix[column][entry]),
                    )
                    matrix[row][entry] = divideExact(numerator, previous)
                }
                matrix[row][column] = Polynomial.ZERO
            }
            previous = pivot
        }
        val result = matrix.last().last()
        return if (negative) negate(result) else result
    }
}
