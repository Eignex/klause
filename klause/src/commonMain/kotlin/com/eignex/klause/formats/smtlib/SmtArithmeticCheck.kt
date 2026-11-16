package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.RealCertificationUnavailable
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.arithmetic.transcendental.TranscendentalResourceLimit
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_TWO
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.remainderOf
import com.eignex.util.numbers.shr
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus

internal enum class SmtArithmeticOperation { DIV_MOD, REAL_DIV, FLOOR, POWER }

internal class SmtArithmeticCheck(private val operation: SmtArithmeticOperation,
    private val coordinates: List<String>) : SmtSourceCheck {
    override fun check(values: SmtModelValues): Boolean = try { checkOperation(values) }
    catch (_: TranscendentalResourceLimit) { false }
    catch (_: TranscendentalInterrupted) { false }
    catch (_: PolynomialResourceLimit) { false }
    catch (_: PolynomialInterrupted) { false }

    private fun checkOperation(values: SmtModelValues): Boolean = when (operation) {
        SmtArithmeticOperation.DIV_MOD -> {
            val (a, b, q, r) = coordinates.map { parseBigInt(values.integer(it)) }
            b == BIG_ZERO || a == b * q + r && r >= BIG_ZERO && r < b.abs()
        }
        SmtArithmeticOperation.POWER -> {
            val (base, exponent, actual) = coordinates.map { parseBigInt(values.integer(it)) }
            val expected = when {
                exponent == BIG_ZERO -> BIG_ONE
                base == BIG_ONE -> BIG_ONE
                base == -BIG_ONE -> if (exponent.remainderOf(BIG_TWO) == BIG_ZERO) BIG_ONE else -BIG_ONE
                exponent < BIG_ZERO || base == BIG_ZERO -> BIG_ZERO
                else -> integerPower(base, exponent, values)
            }
            actual == expected
        }
        SmtArithmeticOperation.REAL_DIV, SmtArithmeticOperation.FLOOR -> checkReals(values)
    }

    private fun integerPower(base: BigInt, exponent: BigInt, values: SmtModelValues): BigInt {
        val arithmetic = TranscendentalArithmetic(cancellation = values.cancellation)
        var remaining = exponent
        var factor = BigFraction.of(base, BIG_ONE)
        var result = BigFraction.ONE
        while (remaining > BIG_ZERO) {
            values.cancellation.polynomialSourceCheckpoint(1)
            if (remaining.remainderOf(BIG_TWO) == BIG_ONE) result = arithmetic.multiply(result, factor)
            remaining = remaining shr 1
            if (remaining > BIG_ZERO) factor = arithmetic.multiply(factor, factor)
        }
        return result.num
    }

    private fun checkReals(values: SmtModelValues): Boolean {
        val arithmetic = ExactRealArithmetic(TranscendentalArithmetic(cancellation = values.cancellation),
            AlgebraicArithmetic(PolynomialArithmetic(cancellation = values.cancellation)))
        fun real(name: String): ExactRealValue = values.exactReal(name) ?: throw RealCertificationUnavailable()
        return try {
            val a = real(coordinates[0])
            if (operation == SmtArithmeticOperation.REAL_DIV) {
                val b = real(coordinates[1])
                val zero = arithmetic.rational(BigFraction.ZERO)
                if (arithmetic.compare(b, zero) == 0) true
                else arithmetic.compare(a, arithmetic.multiply(listOf(b, real(coordinates[2])))) == 0
            } else {
                val q = arithmetic.rational(BigFraction.of(parseBigInt(values.integer(coordinates[1])), BIG_ONE))
                val next = arithmetic.add(listOf(q, arithmetic.rational(BigFraction.ONE)))
                val lower = arithmetic.compare(q, a)
                val upper = arithmetic.compare(a, next)
                lower != null && lower <= 0 && upper != null && upper < 0
            }
        } catch (_: RealCertificationUnavailable) {
            false
        }
    }
}

internal class SmtArithmeticCongruenceCheck(private val applications: List<List<String>>,
    private val real: Boolean) : SmtSourceCheck {
    override fun check(values: SmtModelValues): Boolean {
        for (first in applications.indices) for (second in first + 1 until applications.size) {
            values.cancellation.polynomialSourceCheckpoint(1)
            val a = applications[first]
            val b = applications[second]
            if (real) {
                val numeratorEqual = values.equal(a[0], b[0])
                val denominatorEqual = values.equal(a[1], b[1])
                if (numeratorEqual == false || denominatorEqual == false) continue
                if (numeratorEqual == null || denominatorEqual == null) return false
                if (values.equal(a[2], b[2]) != true) return false
            } else if (parseBigInt(values.integer(a[0])) == parseBigInt(values.integer(b[0])) &&
                parseBigInt(values.integer(a[1])) == parseBigInt(values.integer(b[1]))) {
                if (parseBigInt(values.integer(a[2])) != parseBigInt(values.integer(b[2])) ||
                    parseBigInt(values.integer(a[3])) != parseBigInt(values.integer(b[3]))) return false
            }
        }
        return true
    }
}
