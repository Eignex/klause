package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.ionspin.kotlin.bignum.integer.BigInteger

internal fun exactPointWitness(
    model: LpModel,
    primal: DoubleArray,
    observer: LpCertificationObserver? = null,
): ExactLpWitness? {
    val point = if (primal.size == model.n && primal.all { it.isFinite() } && model.finiteExactInput()) {
        checkedLpWitness(model, primal.map { checkNotNull(BigFraction.ofDouble(it)) }) ?: run {
            var common = BigInteger.ONE
            val limit = BigInteger.fromLong(MAX_POINT_DENOMINATOR)
            val candidate = primal.map { value ->
                val part = reconstructRational(value, maxDenominator = MAX_POINT_DENOMINATOR) ?: return@run null
                val denominator = BigInteger.fromLong(part.denominator)
                common = common / common.gcd(denominator) * denominator
                if (common > limit) return@run null
                BigFraction.of(BigInteger.fromLong(part.numerator), denominator)
            }
            checkedLpWitness(model, candidate)
        }
    } else {
        null
    }
    observer?.observe(LpCertifier.EXACT_POINT, point != null)
    return point
}

internal fun exactPointFeasible(
    model: LpModel,
    primal: DoubleArray,
    observer: LpCertificationObserver? = null,
): Boolean = exactPointWitness(model, primal, observer) != null

private const val MAX_POINT_DENOMINATOR = 1L shl 40
