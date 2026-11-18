package com.eignex.klause.lp

import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.LpRowSlack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextUp

internal fun RelaxationBuilder.realResidualSlack(result: Int, expressionMagnitude: Double): LpRowSlack =
    LpRowSlack(doubleArrayOf(1.0), doubleArrayOf((realMagnitude(result) + expressionMagnitude).nextUp()))

internal fun RelaxationBuilder.realConstantSlack(result: Int, value: Double): LpRowSlack =
    LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(max(realMagnitude(result), abs(value))))

internal interface SourceRowSlackBuilder : RelaxationBuilder {
    fun sourceRowSlack(row: LinearRow): LpRowSlack?
    fun sourceIntMagnitude(intVar: Int): Double
}

internal fun LinearRow.realSourceSlack(builder: SourceRowSlackBuilder): LpRowSlack? {
    val source = constants as? RealConstants ?: return null
    if (!source.bound.isFinite()) return null
    var activity = 0.0
    for (index in 0 until size) {
        val coefficient = if (index < source.intCoefficients.size) source.intCoefficients.at(index)
        else source.realCoefficients.at(index - source.intCoefficients.size)
        if (!coefficient.isFinite()) return null
        if (coefficient == 0.0) continue
        val reference = ref(index)
        val magnitude = when {
            Term.isBool(reference) -> 1.0
            Term.isInt(reference) -> builder.sourceIntMagnitude(Term.intVar(reference))
            else -> builder.realMagnitude(Term.realVar(reference))
        }
        activity = (activity + (abs(coefficient) * magnitude).nextUp()).nextUp()
    }
    return LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(max(abs(source.bound), activity)))
}
