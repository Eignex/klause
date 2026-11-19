package com.eignex.klause.lp

import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpCertifierCost
import com.eignex.klause.lp.engine.LpClaim
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal fun sourceLpPoint(
    rows: List<ExactRationalInequality>,
    variables: Int,
    budget: SourceLpBudget,
    cancellation: ExecutionControl,
): List<BigFraction>? {
    if (variables < 0 || cancellation()) return null
    val policy = SparseSourceLpPolicy()
    return budget.runSparse(cancellation, { token ->
        SparseSourceLp.prepare(rows, ConstantList(variables, ExactLpColumn(ExactLpBounds(), integral = false)),
            ConstantList(variables, BigFraction.ZERO), BigFraction.ZERO, policy, token, pointMargin = true)
    }) { prepared, token ->
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                if (cost is LpCertifierCost.Metered) {
                    token.charge(cost.work)
                    budget.observe(refinement = cost.work)
                }
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                token.charge(metrics.work)
                budget.observe(refinement = metrics.work)
                budget.onBasisVerification?.invoke(metrics)
            }
            override fun observeContinuation(metrics: ExactContinuationMetrics) {
                token.charge(metrics.work)
                budget.observe(continuation = metrics.work)
            }
        }
        val owner = LpScopedSolver(LpExactState(prepared.model), token, context = budget.context,
            iterationLimit = policy.floating.iterations, workLimit = policy.floating.work)
        try {
            val margin = prepared.pointMarginColumn
            val result = owner.solve(token = token, observer = observer, sparsePointRecovery = true,
                claim = if (margin == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM)
            val primal = result?.exactPrimal ?: return@runSparse null
            if (margin != null && primal.getOrNull(margin)?.signum() != 1) return@runSparse null
            val validationWork = variables.toLong() + rows.sumOf { it.columns.size.toLong() + 1L }
            token.charge(validationWork)
            budget.observe(refinement = validationWork)
            if (token()) return@runSparse null
            val point = primal.take(variables)
            point.takeIf {
                it.size == variables && it.admittedSourcePoint() && it.satisfiesSourceRows(rows, token) && !token()
            }
        } finally {
            budget.observe(preparation = owner.metrics.preparationWork, floatWork = owner.lastMetrics.workOps)
            budget.closeOwner(owner)
        }
    }
}

// Sparse admission reserves the metadata before a dimension-sized list is materialized.
private class ConstantList<T>(override val size: Int, private val value: T) : AbstractList<T>() {
    override fun get(index: Int): T {
        require(index in indices)
        return value
    }
}
