package com.eignex.klause.ir

import com.eignex.klause.simplex.exact.BigFraction

/** A source coordinate appearing in an exact quadratic objective. */
sealed interface QuadraticVariable {
    /** Boolean coordinates have the numeric values zero and one. */
    data class Bool(val id: SourceBoolId) : QuadraticVariable

    /** Integer coordinates retain their exact source value. */
    data class Int(val id: SourceIntId) : QuadraticVariable

    /** Real coordinates may have rational or algebraic values. */
    data class Real(val id: SourceRealId) : QuadraticVariable
}

/** The literal term `coefficient * first`, or `coefficient * first * second`. */
data class QuadraticObjectiveTerm(
    /** Exact source coefficient, without Hessian scaling. */
    val coefficient: BigFraction,
    /** First source coordinate. */
    val first: QuadraticVariable,
    /** Second source coordinate; repeating [first] expresses a square. */
    val second: QuadraticVariable? = null,
)

/** An immutable source quadratic objective; direction belongs to the optimization request. */
class QuadraticObjectiveSpec(
    terms: List<QuadraticObjectiveTerm>,
    /** Exact constant term. */
    val constant: BigFraction = BigFraction.ZERO,
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
