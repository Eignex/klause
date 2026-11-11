package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpNumericalResult
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.floatingOptimumAcceptance
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.Cancellation
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.collections.IntHashSet

internal class LeafRealAcceptance(
    private val relaxation: LpRelaxation,
    private val problem: Problem,
    private val sample: Sample,
    private val sourceCheck: ((Sample) -> Boolean)?,
    private val cancellation: Cancellation,
    numerical: Boolean,
) {
    private var acceptedResult: FloatLpResult? = null
    private var acceptedPoint: Sample? = null

    val predicate: ((FloatLpResult) -> Boolean)? = if (numerical) ::acceptNumerical else {
        sourceCheck.floatingOptimumAcceptance()?.let { check ->
            { result -> check(sample.copy(reals = relaxation.floatReals(result.primal, problem))) }
        }
    }

    fun leafResult(result: LpNumericalResult): LeafRealResult {
        if (cancellation()) return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
        if (result.conflict?.belongsTo(relaxation.model) == true) {
            val factors = IntHashSet()
            for (factor in relaxation.rowFactorIds) {
                cancellation.charge(1L)
                if (cancellation()) return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
                if (factor >= 0) factors.add(factor)
            }
            return LeafRealResult(LpVerdict.INFEASIBLE, emptyDoubleArray(),
                refutingFactors = factors.toIntArray(), numerical = true)
        }
        if (problem.factors.any { it is ContinuousProduct && it.finiteChoices?.hasGuards != true }) {
            return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
        }
        val point = acceptedPoint.takeIf { acceptedResult != null && result.point === acceptedResult }
            ?: return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
        val optimum = result.optimumWithinTolerance && result.bound?.belongsTo(relaxation.model) == true
        return LeafRealResult(if (optimum) LpVerdict.TOLERANCE_OPTIMUM else LpVerdict.FEASIBLE,
            point.reals, point.exactReals, numerical = true)
    }

    private fun acceptNumerical(result: FloatLpResult): Boolean {
        acceptedResult = null
        acceptedPoint = null
        if (cancellation()) return false
        val reals = relaxation.floatReals(result.primal, problem)
        if (reals.any { !it.isFinite() } || cancellation()) return false
        val exact = if (sourceCheck.permitsToleranceOptimum()) null else {
            val values = ArrayList<BigFraction>(reals.size)
            for (value in reals) {
                cancellation.charge(1L)
                if (cancellation()) return false
                values += BigFraction.ofDouble(value) ?: return false
            }
            values
        }
        val point = sample.copy(reals = reals, exactReals = exact)
        // Checking source arithmetic on a proposed point does not certify an LP bound or optimum.
        val accepted = sourceCheck?.invoke(point)
            ?: (verifyRealCoordinates(problem, point, cancellation, checkDiscrete = false) is Verification.Accepted)
        if (!accepted || cancellation()) return false
        acceptedResult = result
        acceptedPoint = point
        return true
    }
}
