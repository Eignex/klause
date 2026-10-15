package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivModExpression
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.toWide
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.abs
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.isZero
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.toLongExact

internal fun Compiler.Builder.retainedEuclideanDivMod(a: IntComb, b: IntComb, quotient: Boolean): IntComb {
    val bw = b.toWide()
    if (bw.coeffs.isNotEmpty()) smtUnsupported("non-constant divisor in div/mod")
    val divisor = bw.constant
    if (divisor.isZero()) smtUnsupported("division by zero in div/mod")
    val key = SmtDivModKey(a.toWide(), divisor)
    val outputs = divModTerms.getOrPut(key) {
        val magnitude = divisor.abs()
        val q = freshWideInt(intCombMagnitude(a)?.div(magnitude)?.plus(BIG_ONE))
        val r = freshWideInt(magnitude - BIG_ONE)
        val range = wideRange(a.toWide())
        val affine = a is IntComb.Wide || q is IntComb.Wide || r is IntComb.Wide ||
            (range != null && (!range.first.fitsLong() || !range.second.fitsLong()))
        factors += if (affine) {
            fun expression(value: IntComb): DivModExpression {
                val wide = value.toWide()
                return DivModExpression(wide.coeffs, wide.constant)
            }
            WideDivMod(expression(a), divisor, expression(q), expression(r))
        } else DivMod(divModColumn(a), divisor, divModColumn(q), divModColumn(r), DivisionConvention.EUCLIDEAN)
        q to r
    }
    return if (quotient) outputs.first else outputs.second
}

private fun Compiler.Builder.divModColumn(expression: IntComb): Int {
    val wide = expression.toWide()
    if (wide.constant.isZero() && wide.coeffs.size == 1 && wide.coeffs.values.single() == BIG_ONE) {
        return wide.coeffs.keys.single()
    }
    val range = when (expression) {
        is IntComb.Narrow -> linCombRange(expression.lin)
        is IntComb.Wide -> wideRange(wide)?.let { (lo, hi) ->
            lo.takeIf { it.fitsLong() }?.toLongExact() to hi.takeIf { it.fitsLong() }?.toLongExact()
        } ?: (null to null)
    }
    val column = newInt(range.first, range.second)
    assertRelation("=", IntComb.Narrow(LinComb(mapOf(column to 1L), 0L)), expression)
    return column
}
