package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.util.Cancellation

internal class BooleanProofRecipe(
    val problem: BakedProblem,
    val params: BacktrackParams,
    val reserve: (Cancellation, () -> ResumableSolve) -> ResumableSolve,
) {
    fun deterministicClauseQuery(): Boolean = params.copy(
        randomSeed = null, nodeBudget = null, cancellation = Cancellation.Never, assumptions = Assumptions.None,
        onEvent = null, clauseExchange = null,
    ) == BacktrackParams(variableSelector = InputOrder, valueSelector = IndomainMin, nativeSat = false)

    fun conditioned(): BacktrackParams? {
        val exchange = params.clauseExchange
        if (exchange != null && exchange !is PoolClauseExchange) return null
        return params.copy(clauseExchange = (exchange as? PoolClauseExchange)?.conditioned())
    }
}
