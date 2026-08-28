package com.eignex.klause.ir

import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyLongArray

/** Immutable linear objective coefficients produced by lowering. */
data class LinearObjectiveSpec(
    /** Weight of each Boolean variable when true, indexed by Boolean variable id. */
    val boolWeights: LongArray = EmptyLongArray,
    /** Coefficient of each integer variable, indexed by integer variable id. */
    val intCoefficients: LongArray = EmptyLongArray,
    /** Constant term. */
    val constant: Long = 0L,
    /** Coefficient of each LP-only real variable, indexed by real variable id. */
    val realCoefficients: DoubleArray = EmptyDoubleArray,
) {
    override fun equals(other: Any?): Boolean = other is LinearObjectiveSpec &&
        constant == other.constant &&
        boolWeights.contentEquals(other.boolWeights) &&
        intCoefficients.contentEquals(other.intCoefficients) &&
        realCoefficients.contentEquals(other.realCoefficients)

    override fun hashCode(): Int {
        var h = constant.hashCode()
        h = 31 * h + boolWeights.contentHashCode()
        h = 31 * h + intCoefficients.contentHashCode()
        h = 31 * h + realCoefficients.contentHashCode()
        return h
    }
}
