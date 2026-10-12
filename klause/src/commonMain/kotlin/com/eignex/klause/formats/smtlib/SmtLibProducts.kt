package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.isConstant
import com.eignex.klause.lowering.scaleByConst
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal fun Compiler.Builder.integerProduct(parts: List<IntComb>): IntComb {
    if (inferringBounds || macros.isNotEmpty()) smtUnsupported("nonlinear product requires source lowering")
    var result: IntComb = IntComb.Narrow(LinComb(emptyMap(), 1L))
    for (part in parts) {
        result = if (result.isConstant()) scaleByConst(part, result)
        else if (part.isConstant()) scaleByConst(result, part)
        else {
            val left = (result as? IntComb.Narrow)?.lin ?: smtUnsupported("wide nonlinear product")
            val right = (part as? IntComb.Narrow)?.lin ?: smtUnsupported("wide nonlinear product")
            val (leftLo, leftHi) = finiteProductRange(left)
            val (rightLo, rightHi) = finiteProductRange(right)
            val endpoints = listOf(leftLo, leftHi).flatMap { a ->
                listOf(rightLo, rightHi).map { b -> bigIntOf(a) * bigIntOf(b) }
            }
            if (endpoints.any { !it.fitsLong() || it == bigIntOf(Long.MIN_VALUE) }) {
                smtUnsupported("nonlinear product exceeds signed word range")
            }
            val values = endpoints.map { it.toLongExact() }
            val value = newInt(values.min(), values.max())
            factors.add(Product(productIntColumn(left), productIntColumn(right), value))
            IntComb.Narrow(LinComb(mapOf(value to 1L), 0))
        }
    }
    return result
}

private fun Compiler.Builder.finiteProductRange(term: LinComb): Pair<Long, Long> {
    var lo = bigIntOf(term.constant)
    var hi = lo
    for ((id, coefficient) in term.coeffs) {
        if (coefficient == 0L) continue
        val domain = intDomains[id]
        val lower = when (domain) {
            is PresolveDomain.Finite -> domain.domain.min
            is PresolveDomain.Open -> domain.lo
        } ?: smtUnsupported("nonlinear product requires finite operands")
        val upper = when (domain) {
            is PresolveDomain.Finite -> domain.domain.max
            is PresolveDomain.Open -> domain.hi
        } ?: smtUnsupported("nonlinear product requires finite operands")
        val scale = bigIntOf(coefficient)
        lo += scale * bigIntOf(if (coefficient >= 0) lower else upper)
        hi += scale * bigIntOf(if (coefficient >= 0) upper else lower)
    }
    if (!lo.fitsLong() || !hi.fitsLong() || lo == bigIntOf(Long.MIN_VALUE) || hi == bigIntOf(Long.MIN_VALUE) ||
        term.constant == Long.MIN_VALUE || term.coeffs.values.any { it == Long.MIN_VALUE }
    ) smtUnsupported("nonlinear product requires representable operands")
    return lo.toLongExact() to hi.toLongExact()
}

private fun Compiler.Builder.productIntColumn(term: LinComb): Int {
    val (lo, hi) = finiteProductRange(term)
    term.asSimpleVar()?.let { return it }
    val value = newInt(lo, hi)
    postLinearRel(LinComb(mapOf(value to 1L), 0), term, LinearOp.EQ)
    return value
}

internal fun Compiler.Builder.realProduct(parts: List<RealComb>): Res.R {
    if (inferringBounds || macros.isNotEmpty()) smtUnsupported("nonlinear product requires source lowering")
    var result = RealComb(emptyMap(), emptyMap(), BigFraction.ONE)
    for (part in parts) {
        result = when {
            result.isConstant -> part.scaled(result.constant)
            part.isConstant -> result.scaled(part.constant)
            else -> {
                val leftInt = result.toLinCombOrNull()
                val rightInt = part.toLinCombOrNull()
                if (leftInt != null && rightInt != null) {
                    when (val product = integerProduct(listOf(IntComb.Narrow(leftInt), IntComb.Narrow(rightInt)))) {
                        is IntComb.Narrow -> product.lin.toRealComb()
                        is IntComb.Wide -> product.lin.toRealComb()
                    }
                } else {
                    val value = nextReal++
                    val left = if (leftInt != null) mixedProductIntColumn(leftInt) else productRealColumn(result)
                    val right = if (rightInt != null) mixedProductIntColumn(rightInt) else productRealColumn(part)
                    factors.add(if (leftInt != null) {
                        RealProduct(left, right, value, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                    } else if (rightInt != null) {
                        RealProduct(right, left, value, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                    } else ContinuousProduct(left, right, value))
                    RealComb(emptyMap(), mapOf(value to BigFraction.ONE), BigFraction.ZERO)
                }
            }
        }
    }
    return Res.R(result)
}

private fun Compiler.Builder.mixedProductIntColumn(term: LinComb): Int {
    val (lo, hi) = finiteProductRange(term)
    val exactMagnitude = 1L shl 53
    if (lo < -exactMagnitude || hi > exactMagnitude) {
        smtUnsupported("mixed product integer operand exceeds exact floating coefficient range")
    }
    return productIntColumn(term)
}

private fun Compiler.Builder.productRealColumn(term: RealComb): Int {
    if (term.intCoeffs.isEmpty() && term.constant.isZero && term.realCoeffs.size == 1) {
        val (id, coefficient) = term.realCoeffs.entries.single()
        if (coefficient == BigFraction.ONE) return id
    }
    val value = nextReal++
    val column = RealComb(emptyMap(), mapOf(value to BigFraction.ONE), BigFraction.ZERO)
    factors.add(Clause(intArrayOf(reifyRealRel("=", column, term))))
    return value
}

private fun exponent(term: IntComb): Int {
    val value = when (term) {
        is IntComb.Narrow -> {
            if (!term.isConstant()) smtUnsupported("power requires a fixed nonnegative integer exponent")
            bigIntOf(term.lin.constant)
        }
        is IntComb.Wide -> {
            if (!term.isConstant()) smtUnsupported("power requires a fixed nonnegative integer exponent")
            term.lin.constant
        }
    }
    if (value < bigIntOf(0) || value > bigIntOf(Int.MAX_VALUE)) smtUnsupported("power exponent exceeds representation")
    return value.toLongExact().toInt()
}

internal fun Compiler.Builder.integerPower(base: IntComb, power: IntComb): IntComb {
    var remaining = exponent(power)
    var result: IntComb = IntComb.Narrow(LinComb(emptyMap(), 1L))
    var factor = base
    while (remaining > 0) {
        if (remaining and 1 != 0) result = integerProduct(listOf(result, factor))
        remaining = remaining ushr 1
        if (remaining > 0) factor = integerProduct(listOf(factor, factor))
    }
    return result
}

internal fun Compiler.Builder.realPower(base: RealComb, power: IntComb): Res.R {
    var remaining = exponent(power)
    var result = RealComb(emptyMap(), emptyMap(), BigFraction.ONE)
    var factor = base
    while (remaining > 0) {
        if (remaining and 1 != 0) result = realProduct(listOf(result, factor)).comb
        remaining = remaining ushr 1
        if (remaining > 0) factor = realProduct(listOf(factor, factor)).comb
    }
    return Res.R(result)
}
