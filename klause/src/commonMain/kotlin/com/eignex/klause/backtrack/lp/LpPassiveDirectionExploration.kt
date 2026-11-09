package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef

internal class LpPassiveDirectionExploration(
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
    maxChecks: Int = 128,
    maxSelections: Int = 32,
) {
    init {
        require(maxChecks in 0..128 && maxSelections in 0..32)
    }

    private val choice = LpPassiveDirectionChoice(true, observe, charge, LpPassiveDirectionChoice.Kind.EXPLORATION)
    private var remainingChecks = maxChecks
    private var remainingSelections = maxSelections

    val available: Boolean get() = remainingChecks > 0 && remainingSelections > 0
    val hasNomination: Boolean get() = choice.hasNomination

    fun clear() = choice.clear()

    fun select(
        variable: VarRef,
        point: Double,
        alternatives: List<SearchDecision>,
        history: LpDirectionalHistory,
        solveStamp: Int,
        fresh: () -> Boolean,
    ): List<SearchDecision> {
        clear()
        if (!available) return alternatives
        remainingChecks--
        charge(16L)
        observe("PASSIVE_EXPLORATION_CHECK")
        if (!fresh()) return alternatives.also { observe("PASSIVE_EXPLORATION_CHECK_STALE") }
        val preferUp = history.preferUnderObservedDirection(variable, point)
            ?: return alternatives.also { observe("PASSIVE_EXPLORATION_NO_PROPOSAL") }
        remainingSelections--
        return choice.select(variable, point, preferUp, alternatives, solveStamp, fresh)
    }

    fun commit(decision: SearchDecision, solveStamp: Int, fresh: () -> Boolean) =
        choice.commit(decision, solveStamp, fresh)
}
