package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalMinimum
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.util.Cancellation

internal sealed interface LpReferenceResult {
    class Feasible(
        val witness: List<BigFraction>,
        val objectiveLowerBound: BigFraction?,
        val lowerBoundAttained: Boolean,
        val objectiveDecline: LpReferenceDecline? = null,
    ) : LpReferenceResult

    data object Infeasible : LpReferenceResult

    class Declined(val reason: LpReferenceDecline) : LpReferenceResult
}

internal enum class LpReferenceDecline {
    CANCELLED_OR_PIVOT_LIMIT,
    NON_FINITE_INPUT,
    PROBE_BOUND_OBJECTIVE,
}

/**
 * Test-only exact reference for the public LP model seam.
 *
 * Every finite [LpModel] datum is eligible because the adapter reads the exact model directly and
 * converts real inputs from their IEEE representation into [BigFraction]. A run declines when
 * cancellation or [maxPivots] interrupts an exact simplex call, or when a non-finite input reaches the
 * seam. Feasibility remains usable while objective comparison declines if a finite probe stands in for
 * an objective-improving open side. The feasibility witness and objective lower bound stay separate: a
 * finite infimum is attained only when the exact witness reaches it.
 */
internal class LpReferenceAdapter(
    private val cancellation: Cancellation = Cancellation.Never,
    private val maxPivots: Int = Int.MAX_VALUE,
) {
    fun solve(model: LpModel): LpReferenceResult {
        val feasibility = bigRationalOutcome(model, cancellation, maxPivots)
        when (feasibility.feasibility) {
            RationalFeasibility.INFEASIBLE -> return LpReferenceResult.Infeasible
            RationalFeasibility.UNKNOWN -> return declined()
            RationalFeasibility.FEASIBLE -> Unit
        }
        val costs = List(model.n) { column ->
            exact(model.costD(column)) ?: return LpReferenceResult.Declined(LpReferenceDecline.NON_FINITE_INPUT)
        }
        val shifts = List(model.n) { column ->
            exact(model.loShiftD(column)) ?: return LpReferenceResult.Declined(LpReferenceDecline.NON_FINITE_INPUT)
        }
        val shiftedWitness = checkNotNull(feasibility.witness).mapIndexed { column, value -> value + shifts[column] }
        val witnessObjective = shiftedWitness.foldIndexed(BigFraction.ZERO) { column, total, value ->
            total + costs[column] * value
        }
        val probeMaySupportObjective = costs.indices.any { column ->
            val sign = costs[column].signum()
            (sign > 0 && model.probeClampedLo[column]) ||
                (sign < 0 && model.probeClampedHi[column])
        }
        if (probeMaySupportObjective) {
            return LpReferenceResult.Feasible(
                shiftedWitness,
                objectiveLowerBound = null,
                lowerBoundAttained = false,
                objectiveDecline = LpReferenceDecline.PROBE_BOUND_OBJECTIVE,
            )
        }
        val optimum = bigRationalMinimum(model, costs, cancellation, maxPivots)
        if (optimum.feasibility == RationalFeasibility.UNKNOWN) return declined()
        if (optimum.feasibility == RationalFeasibility.INFEASIBLE) return LpReferenceResult.Infeasible
        val lowerBound = if (optimum.unbounded) {
            null
        } else {
            val constant = exact(model.objConstantD)
                ?: return LpReferenceResult.Declined(LpReferenceDecline.NON_FINITE_INPUT)
            checkNotNull(optimum.infimum) + constant
        }
        return LpReferenceResult.Feasible(
            shiftedWitness,
            lowerBound,
            lowerBoundAttained = lowerBound != null && witnessObjective == lowerBound,
        )
    }

    private fun declined(): LpReferenceResult.Declined =
        LpReferenceResult.Declined(LpReferenceDecline.CANCELLED_OR_PIVOT_LIMIT)

    private fun exact(value: Double): BigFraction? = BigFraction.ofDouble(value)
}
