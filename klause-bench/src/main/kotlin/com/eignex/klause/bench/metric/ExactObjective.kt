package com.eignex.klause.bench.metric

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

internal class ExactObjective private constructor(
    private val numerator: BigInteger,
    private val denominator: BigInteger,
) : Comparable<ExactObjective> {
    override fun compareTo(other: ExactObjective): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    override fun toString(): String =
        if (denominator == BigInteger.ONE) numerator.toString() else "$numerator/$denominator"

    operator fun plus(other: ExactObjective): ExactObjective = normalized(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun times(other: ExactObjective): ExactObjective =
        normalized(numerator * other.numerator, denominator * other.denominator)

    operator fun div(other: ExactObjective): ExactObjective =
        normalized(numerator * other.denominator, denominator * other.numerator)

    val integral: Boolean get() = denominator == BigInteger.ONE

    private fun normalized(n: BigInteger, d: BigInteger): ExactObjective = requireNotNull(parse("$n/$d"))

    fun approximate(): Double? = BigDecimal(numerator).divide(BigDecimal(denominator), MathContext.DECIMAL128)
        .toDouble().takeIf(Double::isFinite)

    companion object {
        fun value(exact: String?, legacy: Double?): ExactObjective? =
            if (exact != null) parse(exact) else legacy?.takeIf(Double::isFinite)?.let { parse(it.toString()) }

        fun parse(text: String): ExactObjective? = runCatching {
            val parts = text.trim().split('/')
            val numerator: BigInteger
            val denominator: BigInteger
            if (parts.size == 1) {
                val decimal = BigDecimal(parts.single())
                numerator = decimal.unscaledValue() * BigInteger.TEN.pow((-decimal.scale()).coerceAtLeast(0))
                denominator = BigInteger.TEN.pow(decimal.scale().coerceAtLeast(0))
            } else {
                require(parts.size == 2)
                numerator = BigInteger(parts[0])
                denominator = BigInteger(parts[1])
            }
            require(denominator != BigInteger.ZERO)
            val divisor = numerator.gcd(denominator)
            val sign = BigInteger.valueOf(denominator.signum().toLong())
            ExactObjective(numerator / divisor * sign, denominator / divisor * sign)
        }.getOrNull()
    }
}
