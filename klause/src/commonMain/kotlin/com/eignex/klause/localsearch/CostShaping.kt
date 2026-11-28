package com.eignex.klause.localsearch

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Combines the violation count and the objective into the scalar greedy descent minimizes. [FeasibilityFirst]
 * considers the objective only at zero violation; [linear] mixes it in from the start so descent can cross
 * slightly infeasible neighbours, and [saturating] caps the violation term. Shaping steers descent only; it
 * does not decide which assignments count as solutions.
 */
sealed interface CostShaping {

    /** Shaped scalar, lower is better; [violationCount] is the summed per-factor violation degree. */
    fun shape(violationCount: Long, objective: Double): Double

    /** True iff descent rejects moves with `violationCount > 0`. */
    val feasibilityGated: Boolean

    /** Two-phase: only optimise the objective once feasibility is reached. */
    data object FeasibilityFirst : CostShaping {
        override fun shape(violationCount: Long, objective: Double): Double =
            if (violationCount == 0L) objective else Double.POSITIVE_INFINITY
        override val feasibilityGated: Boolean = true
    }

    /** `violationPenalty(violationCount) + lambda * objective`. */
    data class Linear(
        /** Weight on the objective term. */
        val lambda: Double = 1.0,
        /** Shaping applied to the violation count. */
        val violationPenalty: ViolationPenalty = ViolationPenalty.Identity,
    ) : CostShaping {
        init {
            require(lambda >= 0) { "lambda must be non-negative, got $lambda" }
        }
        override fun shape(violationCount: Long, objective: Double): Double =
            violationPenalty.of(violationCount) + lambda * objective
        override val feasibilityGated: Boolean = false
    }

    /** Builders for common [CostShaping] blends. */
    companion object {
        /** Linear blend without saturation. */
        fun linear(lambda: Double): CostShaping = Linear(lambda)

        /** Linear blend with the violation contribution capped at [cap]. */
        fun saturating(lambda: Double, cap: Double): CostShaping = Linear(lambda, ViolationPenalty.Saturating(cap))

        /** Linear blend with square-root scaling on violations. */
        fun sqrtViolation(lambda: Double): CostShaping = Linear(lambda, ViolationPenalty.SquareRoot)
    }
}

/** Maps a violation count to a penalty contribution. */
sealed interface ViolationPenalty {
    /** Penalty contribution for [violationCount] violations. */
    fun of(violationCount: Long): Double

    /** Penalty equal to the raw violation count. */
    data object Identity : ViolationPenalty {
        override fun of(violationCount: Long): Double = violationCount.toDouble()
    }

    /** Violation penalty capped at a maximum contribution. */
    data class Saturating(
        /** Maximum penalty the violation term contributes. */
        val cap: Double,
    ) : ViolationPenalty {
        init {
            require(cap >= 0) { "cap must be non-negative, got $cap" }
        }
        override fun of(violationCount: Long): Double = min(violationCount.toDouble(), cap)
    }

    /** Square-root (sub-linear) violation penalty. */
    data object SquareRoot : ViolationPenalty {
        override fun of(violationCount: Long): Double = sqrt(violationCount.toDouble())
    }
}
