package com.eignex.klause.lp

import com.eignex.klause.lp.engine.LpRowSlack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextUp

internal fun RelaxationBuilder.realResidualSlack(result: Int, expressionMagnitude: Double): LpRowSlack =
    LpRowSlack(doubleArrayOf(1.0), doubleArrayOf((realMagnitude(result) + expressionMagnitude).nextUp()))

internal fun RelaxationBuilder.realConstantSlack(result: Int, value: Double): LpRowSlack =
    LpRowSlack(doubleArrayOf(1.0), doubleArrayOf(max(realMagnitude(result), abs(value))))
