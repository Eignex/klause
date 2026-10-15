package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import kotlin.math.abs
import kotlin.math.ceil

internal fun numericalEqualityDegree(expected: Double, actual: Double, cap: Int): Int {
    if (!expected.isFinite() || !actual.isFinite()) return cap
    val scale = maxOf(1.0, abs(expected), abs(actual))
    val residual = abs(expected / scale - actual / scale)
    if (residual <= NUMERICAL_EQUALITY_TOLERANCE) return 0
    return compressViolation(maxOf(1L, ceil(residual / NUMERICAL_EQUALITY_UNIT).toLong()), cap)
}

private const val NUMERICAL_EQUALITY_TOLERANCE = 1e-9
private const val NUMERICAL_EQUALITY_UNIT = 1e-6
