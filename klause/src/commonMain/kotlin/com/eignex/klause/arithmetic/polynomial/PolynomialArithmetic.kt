package com.eignex.klause.arithmetic.polynomial

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialInterrupted : RuntimeException()

internal enum class PolynomialResource { DENSE_COEFFICIENTS, MATRIX_CELLS, DEGREE_OVERFLOW }

internal class PolynomialResourceLimit(val resource: PolynomialResource) : RuntimeException()

internal data class PolynomialLimits(val maxDenseCoefficients: Int? = 65536, val maxMatrixCells: Long? = 262144) {
    init {
        require(maxDenseCoefficients == null || maxDenseCoefficients > 0)
        require(maxMatrixCells == null || maxMatrixCells >= 0)
    }
}

internal class PolynomialArithmetic(
    private val cancellation: Cancellation = Cancellation.Never,
    private val limits: PolynomialLimits = PolynomialLimits(),
) {
    fun step() {
        if (cancellation()) throw PolynomialInterrupted()
        cancellation.charge(1)
        if (cancellation()) throw PolynomialInterrupted()
    }

    fun add(left: Polynomial, right: Polynomial): Polynomial {
        step()
        return Polynomial.of(buildList {
            for (term in left.terms + right.terms) {
                step()
                add(term)
            }
        })
    }

    fun negate(value: Polynomial): Polynomial = scale(value, BigFraction.MINUS_ONE)

    fun subtract(left: Polynomial, right: Polynomial): Polynomial = add(left, negate(right))

    fun scale(value: Polynomial, coefficient: BigFraction): Polynomial {
        step()
        return Polynomial.of(value.terms.map {
            step()
            PolynomialTerm(it.monomial, it.coefficient * coefficient)
        })
    }

    fun multiply(left: Polynomial, right: Polynomial): Polynomial {
        step()
        return Polynomial.of(buildList {
            for (a in left.terms) for (b in right.terms) {
                step()
                for (variable in a.monomial.variables) {
                    if (b.monomial.exponent(variable) > Int.MAX_VALUE - a.monomial.exponent(variable)) {
                        throw PolynomialResourceLimit(PolynomialResource.DEGREE_OVERFLOW)
                    }
                }
                add(PolynomialTerm(a.monomial.times(b.monomial), a.coefficient * b.coefficient))
            }
        })
    }

    fun power(value: Polynomial, exponent: Int): Polynomial {
        require(exponent >= 0)
        step()
        if (value.variables.isEmpty()) {
            val coefficient = value.coefficient(Monomial.ONE)
            val minimumBits = maxOf(coefficient.num.magnitudeBitLength(), coefficient.den.magnitudeBitLength()) - 1L
            if (minimumBits * exponent + 1 > Int.MAX_VALUE) {
                throw PolynomialResourceLimit(PolynomialResource.DEGREE_OVERFLOW)
            }
        }
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

    fun derivative(value: Polynomial, variable: Int): Polynomial {
        step()
        return Polynomial.of(buildList {
            for ((monomial, coefficient) in value.terms) {
                step()
                val degree = monomial.exponent(variable)
                if (degree == 0) continue
                val quotient = checkNotNull(monomial.quotient(Monomial.of(mapOf(variable to 1))))
                add(PolynomialTerm(quotient, coefficient * BigFraction.ofLong(degree.toLong())))
            }
        })
    }

    fun coefficients(value: Polynomial, variable: Int): List<Polynomial> {
        step()
        val groups = List(denseSize(value.degree(variable))) {
            step()
            mutableListOf<PolynomialTerm>()
        }
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
        val matrix = matrix(m.toLong() + n)
        for (row in 0 until n) for (column in a.indices) {
            step()
            matrix[row][row + column] = a[m - column]
        }
        for (row in 0 until m) for (column in b.indices) {
            step()
            matrix[n + row][row + column] = b[n - column]
        }
        return determinant(matrix)
    }

    fun principalSubresultants(left: Polynomial, right: Polynomial, variable: Int): List<Polynomial> {
        step()
        val a = coefficients(left, variable)
        val b = coefficients(right, variable)
        val m = a.lastIndex
        val n = b.lastIndex
        return (0 until minOf(m, n)).map { index ->
            val matrix = matrix(m.toLong() + n - 2L * index)
            val size = matrix.size
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

    fun denseSize(degree: Int): Int {
        step()
        val maximum = limits.maxDenseCoefficients ?: Int.MAX_VALUE
        if (degree >= maximum) throw PolynomialResourceLimit(PolynomialResource.DENSE_COEFFICIENTS)
        return degree + 1
    }

    private fun matrix(size: Long): Array<Array<Polynomial>> {
        step()
        if (size > Int.MAX_VALUE || size * size > (limits.maxMatrixCells ?: Long.MAX_VALUE)) {
            throw PolynomialResourceLimit(PolynomialResource.MATRIX_CELLS)
        }
        return Array(size.toInt()) {
            step()
            Array(size.toInt()) { Polynomial.ZERO }
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
