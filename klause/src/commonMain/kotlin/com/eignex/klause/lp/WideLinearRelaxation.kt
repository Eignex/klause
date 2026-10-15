package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.abs
import com.eignex.klause.util.compareTo

internal class NumericalLinearRow(val coefficients: List<BigFraction>, val rhs: BigFraction)

internal interface ExactRelaxationBuilder : RelaxationBuilder {
    fun exactRow(columns: IntArray, row: NumericalLinearRow, op: LinearOp, indicator: Int, value: Long)
}

internal fun ExactLinear.numericalRow(): NumericalLinearRow? {
    if (relation == LinearOp.NE) return null
    var scale = constants.exactBound.abs()
    for (index in 0 until size) {
        val magnitude = constants.exactCoeff(index).abs()
        if (magnitude > scale) scale = magnitude
    }
    if (scale < BIG_ONE) scale = BIG_ONE
    val coefficients = List(size) { BigFraction.of(constants.exactCoeff(it), scale) }
    val rhs = BigFraction.of(constants.exactBound, scale)
    if (!rhs.projects() || coefficients.any { !it.projects() }) return null
    return NumericalLinearRow(coefficients, rhs)
}

private fun BigFraction.projects(): Boolean {
    val value = toDouble()
    return value.isFinite() && (isZero || value != 0.0)
}
