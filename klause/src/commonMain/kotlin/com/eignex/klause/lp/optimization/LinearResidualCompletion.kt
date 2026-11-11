package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpSparseAdmission
import com.eignex.klause.lp.SparseSourceLp
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.ExactBasisLimits
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.ReconstructionLimits
import com.eignex.klause.lp.engine.computeNullable
import com.eignex.klause.lp.engine.reconstructCertificate
import com.eignex.klause.lp.engine.verifyExactBasis
import com.eignex.klause.simplex.basis.RationalBasisLimits
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation

// One residual owns its continuation and terminal result; changed exact rows retire both.
internal class LinearResidualCompletion(
    private val budget: SourceLpBudget,
    private val policy: SparseSourceLpPolicy,
    private val lifetimeLimit: LpFloatAllowance? = null,
) : AutoCloseable {
    private var rows: List<ExactRationalInequality>? = null
    private var columns = 0
    private var objective: List<BigFraction>? = null
    private var admission: SourceLpSparseAdmission? = null
    private var owner: LpScopedSolver? = null
    private var finished = false
    private var point: List<BigFraction>? = null
    private var floating: FloatLpResult? = null
    private var lifetime: LpFloatAllowance? = null
    private var currentToken = Cancellation.Never
    private val token = object : Cancellation {
        override fun isCancelled() = currentToken()
        override fun deadline() = currentToken.deadline()
        override fun workMeter() = currentToken.workMeter()
    }
    var paused = false
        private set

    fun complete(
        residual: List<ExactRationalInequality>,
        variables: Int,
        cancellation: Cancellation,
        observe: (String, Long) -> Unit,
        costs: List<BigFraction>? = null,
        small: () -> List<BigFraction>?,
    ): List<BigFraction>? {
        paused = false
        if (cancellation()) return null
        val previous = rows
        require(costs == null || costs.size == variables)
        val same = previous != null && columns == variables && objective == costs && previous.size == residual.size &&
            previous.indices.all { id ->
                val a = previous[id]
                val b = residual[id]
                a.columns.contentEquals(b.columns) && a.coefficients == b.coefficients &&
                    a.rhs == b.rhs && a.strict == b.strict
            }
        if (!same) {
            close()
            rows = residual
            columns = variables
            objective = costs?.toList()
            point = small()
            if (point != null) finished = true
            if (cancellation() || budget.funding?.paused == true) {
                paused = !cancellation()
                return point
            }
        } else if (finished) {
            observe(if (point == null) "PrimalRecoveryDeclineReused" else "PrimalRecoveryResidualReused", 1L)
            return point
        }
        if (finished) return point
        currentToken = cancellation
        if (admission == null) {
            val packed = LinearAuthorityModel.prepare(residual, variables, cancellation) ?: return null
            admission = budget.admitSparse(cancellation) { active ->
                SparseSourceLp.prepare(packed.rows, packed.columns, costs ?: List(variables) { BigFraction.ZERO },
                    BigFraction.ZERO, policy, active).also {
                    observe("PrimalRecoverySparseInputEntries", it.metrics.inputEntries)
                    observe("PrimalRecoverySparseInputBits", it.metrics.scalarBits)
                    observe("PrimalRecoverySparsePreparationWork", it.metrics.work)
                    observe("PrimalRecoverySparsePreparationAllocation", it.metrics.allocation)
                    it.decline?.let { reason -> observe("PrimalRecoverySparsePreparation${reason.name}", 1L) }
                }
            }
            if (admission == null) {
                paused = !cancellation() && budget.funding?.paused == true
                finished = !paused && !cancellation()
                return null
            }
        }
        val reservation = checkNotNull(admission)
        while (!cancellation() && budget.funding?.paused != true && !finished) {
            var consulted = false
            budget.resumeSparse(reservation, cancellation) { prepared, active ->
                consulted = true
                currentToken = active
                val funding = budget.funding
                if (funding?.paused == true) return@resumeSparse null
                val profile = lifetime ?: (lifetimeLimit ?: funding?.let { policy.funded(it.remainingWork) }
                    ?: policy.floating)
                    .also { lifetime = it }
                val quantum = minOf(policy.quantum, funding?.availableWork ?: policy.quantum)
                if (quantum <= 0L) return@resumeSparse null
                val current = owner ?: LpScopedSolver(LpExactState(prepared.model), token,
                    context = budget.context, iterationLimit = profile.iterations,
                    workLimit = profile.work).also { owner = it }
                val result = floating ?: run {
                    val preparation = current.metrics.preparationWork
                    try {
                        current.advanceFloat(quantum, profile, token, retainFundingStop = true)?.second
                    } finally {
                        budget.observe(preparation = current.metrics.preparationWork - preparation,
                            floatWork = current.lastMetrics.workOps)
                        observe("PrimalRecoverySparseFloatWork", current.lastMetrics.workOps)
                        observe("PrimalRecoverySparsePivots", current.lastMetrics.pivots.toLong())
                    }
                }
                if (current.floatFundingStopped) {
                    current.lastFloatTermination?.let { observe("PrimalRecoverySparseFloat${it.name}", 1L) }
                    finished = !token()
                    return@resumeSparse null
                }
                if (current.floatPaused) return@resumeSparse null
                if (floating == null) {
                    current.lastFloatTermination?.let { observe("PrimalRecoverySparseFloat${it.name}", 1L) }
                }
                floating = result
                if (token()) return@resumeSparse null
                if (result == null) {
                    finished = true
                    return@resumeSparse null
                }
                if (budget.funding?.paused == true) return@resumeSparse null
                if (budget.context.certificationPolicy.numericalDeductions) {
                    point = numericalPoint(result, residual, token, observe)
                    finished = point != null || (!token() && budget.funding?.paused != true)
                    if (point != null) observe("PrimalRecoveryNumericalCompleted", 1L)
                    return@resumeSparse point
                }
                val model = checkNotNull(current.state.ownerWorkingModel())
                val limits = ReconstructionLimits(maxWork = 2_000_000L,
                    maxAllocation = 64L * 1024L * 1024L)
                var reconstructionWork = 0L
                var reconstructionAllocation = 0L
                point = budget.context.certificationPolicy.computeNullable(LpCertifier.RATIONAL) {
                    val reconstructed = reconstructCertificate(model, primal = result.primal, basis = result.basis,
                        cancellation = token, limits = limits, claim = LpClaim.FEASIBLE_COMPLETION)
                    reconstructionWork = reconstructed.metrics.work
                    reconstructionAllocation = reconstructed.metrics.allocation
                    budget.observe(refinement = reconstructionWork)
                    budget.funding?.charge(0L, reconstructionAllocation)
                    observe("PrimalRecoverySparsePointWork", reconstructionWork)
                    observe("PrimalRecoverySparsePointAllocation", reconstructionAllocation)
                    reconstructed.metrics.decline?.let { observe("PrimalRecoverySparsePoint${it.name}", 1L) }
                    reconstructed.witness
                }?.primal
                if (point == null && !token()) {
                    point = budget.context.certificationPolicy.computeNullable(LpCertifier.EXACT_BASIS) {
                        val checked = verifyExactBasis(model, result.basis, cancellation = token,
                            limits = ExactBasisLimits(
                                RationalBasisLimits(dimension = 512,
                                    work = limits.maxWork - reconstructionWork,
                                    allocationBytes = limits.maxAllocation - reconstructionAllocation),
                                limits), primalOnly = true)
                        budget.observe(refinement = checked.metrics.work)
                        budget.funding?.charge(0L, checked.metrics.allocation)
                        observe("PrimalRecoverySparseBasisWork", checked.metrics.work)
                        observe("PrimalRecoverySparseBasisAllocation", checked.metrics.allocation)
                        observe("PrimalRecoverySparseBasisMaxFill", checked.metrics.peakFill.toLong())
                        observe("PrimalRecoverySparseBasisMaxBits", checked.metrics.maxBits.toLong())
                        observe("PrimalRecoverySparseBasisPhase_${checked.metrics.phase.name}", 1L)
                        checked.metrics.decline?.let { observe("PrimalRecoverySparseBasis${it.name}", 1L) }
                        checked.witness
                    }?.primal
                }
                finished = !token()
                if (point != null) observe("PrimalRecoverySparseCompleted", 1L)
                point
            }
            if (!consulted) {
                finished = !cancellation() && budget.funding?.paused != true
                break
            }
        }
        paused = !finished && !cancellation() && budget.funding?.paused == true
        if (finished) {
            floating = null
            admission?.close()
            admission = null
            budget.closeOwner(owner)
            owner = null
        }
        return point
    }

    private fun numericalPoint(
        result: FloatLpResult, residual: List<ExactRationalInequality>, cancellation: Cancellation,
        observe: (String, Long) -> Unit,
    ): List<BigFraction>? {
        val funding = budget.funding
        val allocation = 128L + 512L * columns
        if (cancellation() || columns > 4096 || result.primal.size < columns ||
            funding?.paused == true || funding?.remainingAllocation?.let { allocation > it } == true) return null
        funding?.charge(0L, allocation)
        budget.observe(preparation = 1L)
        val coordinates = ArrayList<SourceCoordinate>(columns)
        val proposed = ArrayList<BigFraction>(columns)
        for (column in 0 until columns) {
            if (cancellation() || funding?.availableWork?.let { it < 20L } == true) return null
            budget.observe(preparation = 20L)
            coordinates += SourceCoordinate(SourceCoordinate.Kind.Real, column)
            proposed += BigFraction.ofDouble(result.primal[column]) ?: return null
        }
        return proposeLinearPrimal(coordinates, proposed, residual, emptySet(), budget, cancellation, observe)
    }

    override fun close() {
        admission?.close()
        admission = null
        budget.closeOwner(owner)
        owner = null
        rows = null
        objective = null
        point = null
        floating = null
        lifetime = null
        finished = false
        paused = false
    }
}
