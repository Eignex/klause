package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.util.Cancellation

internal class BooleanProofRecipe(
    val solver: BacktrackSolver,
    val params: BacktrackParams,
    val reserve: (Cancellation, () -> ResumableSolve) -> ResumableSolve,
) {
    val problem: BakedProblem get() = solver.problem

    private fun normalized(): BacktrackParams = params.copy(
        randomSeed = null, nodeBudget = null, cancellation = Cancellation.Never, assumptions = Assumptions.None,
        onEvent = null, clauseExchange = null, cutExchange = null, nativeSat = params.nativeSat ?: true,
    )

    fun deterministicClauseQuery(nativeOnly: Boolean = false): Boolean {
        val query = normalized()
        val firstFail = BacktrackParams(variableSelector = SmallestDomain, valueSelector = IndomainMin,
            lubyRestartBase = 256L, nativeSat = true)
        return query == firstFail || (!nativeOnly && query == BacktrackParams(
            variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false,
        ))
    }

    fun matches(other: BooleanProofRecipe): Boolean = normalized() == other.normalized()

    fun conditioned(): BacktrackParams? {
        if (params.lpConfig != null || params.lpPlan != LpPlan()) return null
        if (params.cutExchange != null && params.cutExchange !is PoolCutExchange) return null
        val exchange = params.clauseExchange
        if (exchange != null && exchange !is PoolClauseExchange) return null
        // Satisfaction does not consume the builder's dormant LP cut adapter.
        return params.copy(clauseExchange = (exchange as? PoolClauseExchange)?.conditioned(), cutExchange = null)
    }
}
