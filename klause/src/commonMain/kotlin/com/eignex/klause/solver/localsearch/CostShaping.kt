package com.eignex.klause.solver.localsearch

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Combines hard-constraint violation count with the soft objective into a single scalar the
 * minimize engine uses for greedy descent. The default [FeasibilityFirst] is two-phase:
 * only consider the objective once `violationCount == 0`.
 *
 * For tight problems where the feasible region is narrow or disconnected, [linear] mixes
 * the objective in from the start so the descent can step *through* slightly-infeasible
 * neighbours to reach a strictly better feasible solution on the far side. [saturating]
 * adds a cap on the violation contribution: useful when individual violations are very
 * cheap relative to objective scale and would otherwise dominate.
 *
 * The engine still tracks "best feasible" snapshots separately — shaping only governs the
 * direction of greedy descent, not which assignments count as solutions.
 */
sealed interface CostShaping {

    /** Shaped scalar; lower is better. [violationCount] is the graded total violation
     *  (`LocalSearchState.cost`, a sum of per-factor degrees). */
    fun shape(violationCount: Long, objective: Double): Double

    /** True iff descent should reject moves with `violationCount > 0`. Used to short-circuit
     *  to the greedy-objective code path for [FeasibilityFirst]. */
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

        /** Linear blend with a cap on the violation contribution (one violation can't
         *  dominate). Useful when individual violations are cheap relative to objective. */
        fun saturating(lambda: Double, cap: Double): CostShaping = Linear(lambda, ViolationPenalty.Saturating(cap))

        /** Linear blend with square-root scaling on violations. Sub-linear: each extra
         *  violation contributes less than the previous one. */
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
        /** Maximum penalty any single violation count contributes. */
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
