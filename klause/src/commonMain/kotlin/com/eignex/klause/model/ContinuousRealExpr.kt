package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Exact continuous source expressions; finite coefficients denote binary64 rationals. */
@Serializable
sealed interface ContinuousRealExpr {
    /** `sum(coefficients(name) * name) + constant` over named continuous variables. */
    @Serializable
    @SerialName("continuous_affine")
    data class Affine(
        /** Named real coefficients. */
        val coefficients: Map<String, Double>,
        /** Exact binary64 rational constant. */
        val constant: Double = 0.0,
    ) : ContinuousRealExpr {
        init {
            require(constant.isFinite() && coefficients.values.all { it.isFinite() }) {
                "Continuous expressions require finite coefficients and constants"
            }
        }
    }

    /** Exact product without rounding or constant folding. */
    @Serializable
    @SerialName("continuous_multiply")
    data class Multiply(
        /** First operand. */
        val left: ContinuousRealExpr,
        /** Second operand. */
        val right: ContinuousRealExpr,
    ) : ContinuousRealExpr
}

/** Exact top-level continuous equality; reified and finite-grid contexts decline. */
@Serializable
@SerialName("continuous_equality")
data class ContinuousRealEquality(
    /** Left expression. */
    val left: ContinuousRealExpr,
    /** Right expression. */
    val right: ContinuousRealExpr,
) : BoolExpr
