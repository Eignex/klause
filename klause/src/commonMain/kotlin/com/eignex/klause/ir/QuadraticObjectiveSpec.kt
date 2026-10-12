package com.eignex.klause.ir

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.isZero
import com.eignex.klause.util.signum
import com.eignex.klause.util.unaryMinus

/** A normalized source rational coefficient, independent of numerical engines. */
class QuadraticCoefficient private constructor(
    /** Reduced signed numerator. */
    val numerator: BigInt,
    /** Reduced positive denominator. */
    val denominator: BigInt,
) {
    override fun equals(other: Any?): Boolean = other is QuadraticCoefficient &&
        numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    /** Exact source coefficient factories. */
    companion object {
        /** Additive identity. */
        val ZERO: QuadraticCoefficient = ofLong(0)

        /** Multiplicative identity. */
        val ONE: QuadraticCoefficient = ofLong(1)

        /** Capture an exact integer coefficient. */
        fun ofLong(value: Long): QuadraticCoefficient = of(bigIntOf(value))

        /** Capture and normalize an exact fraction, rejecting a zero denominator. */
        fun of(numerator: BigInt, denominator: BigInt = BIG_ONE): QuadraticCoefficient {
            require(!denominator.isZero()) { "zero quadratic coefficient denominator" }
            val divisor = numerator.gcd(denominator)
            val sign = if (denominator.signum() < 0) -divisor else divisor
            return QuadraticCoefficient(numerator / sign, denominator / sign)
        }
    }
}

/** A source coordinate appearing in an exact quadratic objective. */
sealed interface QuadraticVariable {
    /** Boolean coordinates have the numeric values zero and one. */
    data class Bool(
        /** Location in the source Boolean namespace. */
        val id: SourceBoolId,
    ) : QuadraticVariable

    /** Integer coordinates retain their exact source value. */
    data class Int(
        /** Location in the source integer namespace. */
        val id: SourceIntId,
    ) : QuadraticVariable

    /** Real coordinates may have rational or algebraic values. */
    data class Real(
        /** Location in the source real namespace. */
        val id: SourceRealId,
    ) : QuadraticVariable
}

/** The literal term `coefficient * first`, or `coefficient * first * second`. */
data class QuadraticObjectiveTerm(
    /** Exact source coefficient, without Hessian scaling. */
    val coefficient: QuadraticCoefficient,
    /** First source coordinate. */
    val first: QuadraticVariable,
    /** Second source coordinate; repeating [first] expresses a square. */
    val second: QuadraticVariable? = null,
)

/** An immutable source quadratic objective; direction belongs to the optimization request. */
class QuadraticObjectiveSpec(
    terms: List<QuadraticObjectiveTerm>,
    /** Exact constant term. */
    val constant: QuadraticCoefficient = QuadraticCoefficient.ZERO,
) {
    private val captured = terms.toList()

    /** Independent term list in source order, including repeated terms. */
    val terms: List<QuadraticObjectiveTerm> get() = captured.toList()

    /** Renumber source coordinates, rejecting removal of an objective coordinate. */
    fun remap(remap: VarRemap): QuadraticObjectiveSpec = QuadraticObjectiveSpec(
        captured.map { it.copy(first = it.first.remap(remap), second = it.second?.remap(remap)) }, constant,
    )

    override fun equals(other: Any?): Boolean =
        other is QuadraticObjectiveSpec && constant == other.constant && captured == other.captured

    override fun hashCode(): kotlin.Int = 31 * constant.hashCode() + captured.hashCode()
}

private fun QuadraticVariable.remap(remap: VarRemap): QuadraticVariable = when (this) {
    is QuadraticVariable.Bool -> QuadraticVariable.Bool(requireNotNull(remap.bool(id)))
    is QuadraticVariable.Int -> QuadraticVariable.Int(requireNotNull(remap.int(id)))
    is QuadraticVariable.Real -> QuadraticVariable.Real(requireNotNull(remap.real(id)))
}
