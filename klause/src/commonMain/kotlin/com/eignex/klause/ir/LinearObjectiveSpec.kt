package com.eignex.klause.ir

import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyLongArray

/** Immutable linear objective coefficients produced by lowering. */
class LinearObjectiveSpec(
    /** Weight of each Boolean variable when true, indexed by Boolean variable id. */
    boolWeights: LongArray = EmptyLongArray,
    /** Coefficient of each integer variable, indexed by integer variable id. */
    intCoefficients: LongArray = EmptyLongArray,
    /** Constant term. */
    val constant: Long = 0L,
    /** Coefficient of each LP-only real variable, indexed by real variable id. */
    realCoefficients: DoubleArray = EmptyDoubleArray,
) {
    internal val boolWeightValues: LongArray = boolWeights.copyOf()

    /** Independent copy of the boolWeights coordinates. */
    val boolWeights: LongArray get() = boolWeightValues.copyOf()

    internal val intCoefficientValues: LongArray = intCoefficients.copyOf()

    /** Independent copy of the intCoefficients coordinates. */
    val intCoefficients: LongArray get() = intCoefficientValues.copyOf()

    internal val realCoefficientValues: DoubleArray = realCoefficients.copyOf()

    /** Independent copy of the realCoefficients coordinates. */
    val realCoefficients: DoubleArray get() = realCoefficientValues.copyOf()

    /** Copy this objective, capturing every replacement coefficient buffer. */
    fun copy(
        boolWeights: LongArray = boolWeightValues,
        intCoefficients: LongArray = intCoefficientValues,
        constant: Long = this.constant,
        realCoefficients: DoubleArray = realCoefficientValues,
    ): LinearObjectiveSpec = LinearObjectiveSpec(boolWeights, intCoefficients, constant, realCoefficients)

    /** Independent Boolean coefficients for destructuring. */
    operator fun component1(): LongArray = boolWeights

    /** Independent integer coefficients for destructuring. */
    operator fun component2(): LongArray = intCoefficients

    /** Constant term for destructuring. */
    operator fun component3(): Long = constant

    /** Independent real coefficients for destructuring. */
    operator fun component4(): DoubleArray = realCoefficients

    override fun toString(): String = "LinearObjectiveSpec(boolWeights=${boolWeightValues.contentToString()}, " +
        "intCoefficients=${intCoefficientValues.contentToString()}, constant=$constant, " +
        "realCoefficients=${realCoefficientValues.contentToString()})"

    override fun equals(other: Any?): Boolean = other is LinearObjectiveSpec &&
        constant == other.constant &&
        boolWeightValues.contentEquals(other.boolWeightValues) &&
        intCoefficientValues.contentEquals(other.intCoefficientValues) &&
        realCoefficientValues.contentEquals(other.realCoefficientValues)

    override fun hashCode(): Int {
        var h = constant.hashCode()
        h = 31 * h + boolWeightValues.contentHashCode()
        h = 31 * h + intCoefficientValues.contentHashCode()
        h = 31 * h + realCoefficientValues.contentHashCode()
        return h
    }
}
