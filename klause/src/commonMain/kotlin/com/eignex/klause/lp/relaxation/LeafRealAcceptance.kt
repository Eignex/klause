package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.BudgetedLpPointAcceptance
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpFloatingBoundStop
import com.eignex.klause.lp.engine.LpNumericalResult
import com.eignex.klause.lp.engine.LpPointAcceptanceResult
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.reconstructRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.floatingOptimumAcceptance
import com.eignex.klause.solver.validation.permitsToleranceOptimum
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.Cancellation
import com.eignex.util.arrays.emptyDoubleArray
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.bigIntOf

internal class LeafRealAcceptance(
    private val relaxation: LpRelaxation,
    private val problem: Problem,
    private val sample: Sample,
    private val sourceCheck: ((Sample) -> Boolean)?,
    private val cancellation: Cancellation,
    numerical: Boolean,
) : BudgetedLpPointAcceptance {
    private var acceptedResult: FloatLpResult? = null
    private var acceptedPoint: Sample? = null

    val predicate: ((FloatLpResult) -> Boolean)? = if (numerical) this else {
        sourceCheck.floatingOptimumAcceptance()?.let { check ->
            { result -> check(sample.copy(reals = relaxation.floatReals(result.primal, problem))) }
        }
    }

    fun leafResult(result: LpNumericalResult, objective: LinearObjective?): LeafRealResult {
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
        val recession = result.recession?.takeIf { it.belongsTo(relaxation.model) }
        if (recession != null && objective != null) {
            val ray = sourceDirection(recession.direction)
            val exactPoint = point.exactReals ?: exactCoordinates(point.reals)
            if (ray != null && exactPoint != null && !cancellation() &&
                problem.provesUnbounded(objective, point.copy(exactReals = exactPoint), ray, cancellation)
            ) {
                return LeafRealResult(LpVerdict.UNBOUNDED, point.reals, exactPoint, ray, numerical = true)
            }
            if (cancellation()) return LeafRealResult(LpVerdict.INDETERMINATE, emptyDoubleArray())
        }
        val optimum = result.optimumWithinTolerance && result.bound?.belongsTo(relaxation.model) == true
        return LeafRealResult(if (optimum) LpVerdict.TOLERANCE_OPTIMUM else LpVerdict.FEASIBLE,
            point.reals, point.exactReals, numerical = true)
    }

    private fun sourceDirection(direction: DoubleArray): List<BigFraction>? {
        if (direction.size.toLong() + problem.numRealVars > 262_144L) return null
        return relaxation.realDirection(exactCoordinates(direction) ?: return null, problem)
    }

    private fun exactCoordinates(coordinates: DoubleArray): List<BigFraction>? {
        val exact = ArrayList<BigFraction>(coordinates.size)
        for (value in coordinates) {
            cancellation.charge(1L)
            if (cancellation()) return null
            exact += BigFraction.ofDouble(value) ?: return null
        }
        return exact
    }

    override fun accept(result: FloatLpResult, workLimit: Long): LpPointAcceptanceResult {
        acceptedResult = null
        acceptedPoint = null
        val meter = PointProposalWork(workLimit, cancellation)
        val accepted = try {
            proposePoint(result, meter)
        } catch (_: PointProposalStop) {
            false
        }
        if (cancellation()) {
            acceptedResult = null
            acceptedPoint = null
            return LpPointAcceptanceResult(false, meter.work, LpFloatingBoundStop.CANCELLED)
        }
        return LpPointAcceptanceResult(accepted, meter.work, meter.stop)
    }

    private fun proposePoint(result: FloatLpResult, meter: PointProposalWork): Boolean {
        meter.spend(1L)
        if (problem.numRealVars > 4096) return false
        meter.spend(2L * relaxation.model.n + 4L * problem.numRealVars)
        val reals = relaxation.floatReals(result.primal, problem)
        if (reals.any { !it.isFinite() } || cancellation()) return false
        val tolerant = sourceCheck.permitsToleranceOptimum()
        val exact = if (tolerant) null else reals.map { value ->
            meter.spend(1L)
            BigFraction.ofDouble(value) ?: return false
        }
        val point = sample.copy(reals = reals, exactReals = exact)
        if (acceptPoint(result, point)) return true
        if (tolerant || cancellation()) return false
        meter.spend(3L * reals.size + 1L)
        val rational = reals.map { value ->
            val part = reconstructRational(value, onStep = { meter.spend(1L) }) ?: return false
            BigFraction.of(bigIntOf(part.numerator), bigIntOf(part.denominator))
        }
        if (rational == exact) return false
        val proposed = sample.copy(reals = DoubleArray(rational.size) { rational[it].toDouble() },
            exactReals = rational)
        return acceptPoint(result, proposed)
    }

    private fun acceptPoint(result: FloatLpResult, point: Sample): Boolean {
        if (cancellation()) return false
        // Checking source arithmetic on a proposed point does not certify an LP bound or optimum.
        val accepted = sourceCheck?.invoke(point)
            ?: (verifyRealCoordinates(problem, point, cancellation, checkDiscrete = false) is Verification.Accepted)
        if (!accepted || cancellation()) return false
        acceptedResult = result
        acceptedPoint = point
        return true
    }
}

private class PointProposalStop : RuntimeException()

private class PointProposalWork(private val limit: Long, private val cancellation: Cancellation) {
    var work = 0L
        private set
    var stop: LpFloatingBoundStop? = null
        private set

    init {
        require(limit >= 0L)
    }

    fun spend(units: Long) {
        if (cancellation()) stop = LpFloatingBoundStop.CANCELLED
        else if (units > limit - work) stop = LpFloatingBoundStop.WORK
        if (stop != null) throw PointProposalStop()
        work += units
    }
}
