package com.eignex.klause.lp

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.toDouble
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextUp

internal fun RelaxationBuilder.realResidualSlack(result: Int, expressionMagnitude: Double): LpRowSlack =
    LpRowSlack(doubleArrayOf(1.0), doubleArrayOf((realMagnitude(result) + expressionMagnitude).nextUp()))

internal fun RelaxationBuilder.realConstantSlack(result: Int, value: Double): LpRowSlack =
    LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(max(realMagnitude(result), abs(value))))

internal interface SourceRowSlackBuilder : RelaxationBuilder {
    fun sourceRowSlack(row: LinearRow, includeAbsolute: Boolean = false): LpRowSlack?
    fun sourceIntMagnitude(intVar: Int): Double
    fun sourceBoundMagnitude(intVar: Int, upper: Boolean): Double
    fun sourceRow(row: LinearRow, scale: BigFraction, emit: () -> Unit)
}

internal fun LinearRow.emitSourceLpRow(
    builder: RelaxationBuilder, scale: BigFraction = BigFraction.ONE, emit: () -> Unit,
) {
    if (builder is SourceRowSlackBuilder) builder.sourceRow(this, scale, emit) else emit()
}

internal fun LinearRow.linearSourceSlack(builder: SourceRowSlackBuilder): LpRowSlack? {
    val source = constants
    val bound = when (source) {
        is IntegralConstants -> abs(source.exactBound.toDouble()).nextUp()
        is RealConstants -> abs(source.bound).also { if (!it.isFinite()) return null }
    }
    var activity = 0.0
    for (index in 0 until size) {
        val coefficient = when (source) {
            is IntegralConstants -> abs(source.exactCoeff(index).toDouble())
            is RealConstants -> abs(
                if (index < source.intCoefficients.size) source.intCoefficients.at(index)
                else source.realCoefficients.at(index - source.intCoefficients.size),
            )
        }
        if (source is RealConstants && !coefficient.isFinite()) return null
        if (coefficient == 0.0) continue
        val reference = ref(index)
        val magnitude = when {
            Term.isBool(reference) -> 1.0
            Term.isInt(reference) -> builder.sourceIntMagnitude(Term.intVar(reference))
            else -> builder.realMagnitude(Term.realVar(reference))
        }
        if (magnitude == 0.0) continue
        val outwardCoefficient = if (source is IntegralConstants) coefficient.nextUp() else coefficient
        activity = (activity + (outwardCoefficient * magnitude).nextUp()).nextUp()
    }
    return LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(max(bound, activity)))
}
