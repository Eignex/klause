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
    val (lo, hi) = linCombRange(term)
    if (lo == null || hi == null || lo == Long.MIN_VALUE || hi == Long.MIN_VALUE) {
        smtUnsupported("nonlinear product requires finite representable operands")
    }
    return lo to hi
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
                    val left = if (leftInt != null) productIntColumn(leftInt) else productRealColumn(result)
                    val right = if (rightInt != null) productIntColumn(rightInt) else productRealColumn(part)
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
