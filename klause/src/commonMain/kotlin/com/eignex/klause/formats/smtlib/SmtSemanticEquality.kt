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
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.parseBigInt
import kotlin.coroutines.cancellation.CancellationException

internal class SmtSourceCheckingUnavailable : RuntimeException("SMT source equality is not certified")

internal fun smtExactArithmetic(cancellation: ExecutionControl): ExactRealArithmetic =
    ExactRealArithmetic(TranscendentalArithmetic(cancellation = cancellation),
        AlgebraicArithmetic(PolynomialArithmetic(cancellation = cancellation)))

internal fun smtValuesEqual(values: SmtModelValues, left: SmtValue, right: SmtValue): Boolean? =
    smtEqualityAttempt { SmtValueComparison(values).equal(left, right) }

private class SmtValueComparison(private val values: SmtModelValues) {
    private val arithmetic by lazy { smtExactArithmetic(values.cancellation) }

    fun equal(left: SmtValue, right: SmtValue): Boolean? {
        val work = ArrayDeque<Pair<SmtValue, SmtValue>>()
        work.addLast(left to right)
        var unavailable = false
        while (work.isNotEmpty()) {
            values.checkpoint()
            val (a, b) = work.removeLast()
            val same: Boolean? = when {
                a is SmtValue.Boolean && b is SmtValue.Boolean -> a.value == b.value
                a is SmtValue.Integer && b is SmtValue.Integer -> parseBigInt(a.value) == parseBigInt(b.value)
                a is SmtValue.BitVector && b is SmtValue.BitVector -> a.bits == b.bits
                a is SmtValue.Uninterpreted && b is SmtValue.Uninterpreted ->
                    a.sort == b.sort && a.identity == b.identity
                numeric(a) && numeric(b) -> {
                    val first = real(a)
                    val second = real(b)
                    if (first == null || second == null) null else arithmetic.compare(first, second)?.let { it == 0 }
                }
                a is SmtValue.Application && b is SmtValue.Application -> {
                    if (a.constructor != b.constructor || a.arguments.size != b.arguments.size) false else {
                        for (index in a.arguments.indices) work.addLast(a.arguments[index] to b.arguments[index])
                        true
                    }
                }
                else -> a.semanticEquals(b, values) ?: b.semanticEquals(a, values)
            }
            if (same == false) return false
            if (same == null) unavailable = true
        }
        return if (unavailable) null else true
    }

    private fun numeric(value: SmtValue): Boolean = value is SmtValue.Integer || value is SmtValue.Real

    private fun real(value: SmtValue): ExactRealValue? = when (value) {
        is SmtValue.Integer -> arithmetic.rational(BigFraction.of(parseBigInt(value.value), BIG_ONE))
        is SmtValue.Real -> values.exactReal(value)
        else -> null
    }
}

internal fun smtRationalValue(term: String, cancellation: ExecutionControl): ExactRealValue? = smtEqualityAttempt {
    val arithmetic = smtExactArithmetic(cancellation)
    fun checkpoint(units: Long = 1) {
        cancellation.charge(units)
        if (cancellation()) throw CancellationException("SMT semantic comparison interrupted")
    }
    checkpoint(term.length.toLong())
    val roots = SExprReader(term).readAll()
    if (roots.size != 1) return@smtEqualityAttempt null
    val work = ArrayDeque<Pair<SExpr, Boolean>>()
    val values = ArrayDeque<BigFraction>()
    work.addLast(roots.single() to false)
    while (work.isNotEmpty()) {
        checkpoint()
        val (node, combine) = work.removeLast()
        when (node) {
            is SExpr.Atom -> {
                val pieces = node.text.split('/')
                if (pieces.size == 2 && pieces.all {
                        val digits = it.removePrefix("-")
                        digits.isNotEmpty() && digits.all { c -> c in '0'..'9' }
                    }) {
                    val denominator = parseBigInt(pieces[1])
                    if (denominator == com.eignex.util.numbers.BIG_ZERO) return@smtEqualityAttempt null
                    values.addLast(BigFraction.of(parseBigInt(pieces[0]), denominator))
                } else {
                    val negative = node.text.startsWith('-')
                    val parts = node.text.removePrefix("-").split('.')
                    if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) {
                        return@smtEqualityAttempt null
                    }
                    checkpoint(node.text.length.toLong())
                    val numerator = parseBigInt((if (negative) "-" else "") + parts.joinToString(""))
                    var denominator = BigFraction.ONE
                    if (parts.size == 2) repeat(parts[1].length) {
                        checkpoint()
                        denominator = arithmetic.transcendental.multiply(denominator, BigFraction.ofLong(10))
                    }
                    values.addLast(BigFraction.of(numerator, denominator.num))
                }
            }
            is SExpr.SList -> {
                val head = (node.items.firstOrNull() as? SExpr.Atom)?.text ?: return@smtEqualityAttempt null
                val count = node.items.size - 1
                if (head != "-" && head != "/" || head == "-" && count != 1 || head == "/" && count != 2) {
                    return@smtEqualityAttempt null
                }
                if (combine) {
                    val last = values.removeLast()
                    if (head == "-") values.addLast(last.negated()) else {
                        if (last.isZero) return@smtEqualityAttempt null
                        values.addLast(arithmetic.transcendental.divide(values.removeLast(), last))
                    }
                } else {
                    work.addLast(node to true)
                    for (argument in node.items.drop(1).asReversed()) work.addLast(argument to false)
                }
            }
        }
    }
    arithmetic.rational(values.single())
}

private inline fun <T> smtEqualityAttempt(action: () -> T?): T? = try {
    action()
} catch (_: TranscendentalResourceLimit) {
    null
} catch (_: TranscendentalInterrupted) {
    throw CancellationException("SMT semantic comparison interrupted")
} catch (_: PolynomialResourceLimit) {
    null
} catch (_: PolynomialInterrupted) {
    throw CancellationException("SMT semantic comparison interrupted")
} catch (_: RealCertificationUnavailable) {
    null
} catch (_: UnsupportedSmtException) {
    null
}
