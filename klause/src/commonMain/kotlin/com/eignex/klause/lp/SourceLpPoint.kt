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
import com.eignex.klause.lp.engine.LpVerdict
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
    if (budget.admitsDimensions(variables, rows.size.toLong())) {
        val result = SourceLp(rows, variables, budget).use { owner ->
            owner.solve(cancellation)
        }
        if (cancellation() || result?.verdict == LpVerdict.INFEASIBLE) return null
        result?.exactPrimal?.let { return it.take(variables) }
    }
    val policy = SparseSourceLpPolicy()
    return budget.runSparse(cancellation, { token ->
        SparseSourceLp.prepare(rows, List(variables) { ExactLpColumn(ExactLpBounds(), integral = false) },
            List(variables) { BigFraction.ZERO }, BigFraction.ZERO, policy, token)
    }) { prepared, token ->
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                budget.onBasisVerification?.invoke(metrics)
            }
            override fun observeContinuation(metrics: ExactContinuationMetrics) {
                budget.observe(continuation = metrics.work)
            }
        }
        val owner = LpScopedSolver(LpExactState(prepared.model), token, context = budget.context,
            iterationLimit = policy.floating.iterations, workLimit = policy.floating.work)
        try {
            val result = owner.solve(token = token, observer = observer, sparsePointRecovery = true,
                claim = LpClaim.FEASIBLE_COMPLETION)
            val point = result?.exactPrimal?.take(variables) ?: return@runSparse null
            point.takeIf { it.admittedSourcePoint() && it.satisfiesSourceRows(rows, token) && !token() }
        } finally {
            budget.observe(preparation = owner.metrics.preparationWork, floatWork = owner.lastMetrics.workOps,
                refinement = owner.refinementCache.work)
            budget.closeOwner(owner)
        }
    }
}
