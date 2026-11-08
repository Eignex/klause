package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpPassiveDirectionChoice(
    private val applyAdvice: Boolean,
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
) {
    private var first: SearchDecision? = null
    private var stamp = -1
    private var changed = false

    fun clear() {
        first = null
        changed = false
    }

    fun select(
        variable: VarRef,
        point: Double,
        preferUp: Boolean,
        alternatives: List<SearchDecision>,
        solveStamp: Int,
        fresh: () -> Boolean,
    ): List<SearchDecision> {
        clear()
        charge(16L)
        observe("PASSIVE_DIRECTION_SELECTION")
        if (!fresh()) return alternatives.also { observe("PASSIVE_DIRECTION_STALE") }
        val fraction = point - floor(point)
        if (!point.isFinite() || fraction <= 1e-6 || fraction >= 1.0 - 1e-6) return alternatives
        val down: SearchDecision
        val up: SearchDecision
        when (variable) {
            is VarRef.Bool -> {
                if (point !in 0.0..1.0) return alternatives
                down = SearchDecision.Bool((variable.varId shl 1) or 1)
                up = SearchDecision.Bool(variable.varId shl 1)
            }
            is VarRef.IntVar -> {
                down = SearchDecision.IntAtMost(variable.varId, floor(point).toLong())
                up = SearchDecision.IntAtLeast(variable.varId, floor(point).toLong() + 1L)
            }
        }
        if (alternatives != listOf(down, up) && alternatives != listOf(up, down)) return alternatives
        val proposal = if (preferUp) up else down
        val differs = alternatives.first() != proposal
        if (!fresh()) return alternatives.also { observe("PASSIVE_DIRECTION_STALE") }
        observe("PASSIVE_DIRECTION_COMPARED")
        observe(if (differs) "PASSIVE_DIRECTION_PROPOSAL_CHANGED" else "PASSIVE_DIRECTION_PROPOSAL_AGREED")
        observe(if (applyAdvice) "PASSIVE_DIRECTION_CONSUMED" else "PASSIVE_DIRECTION_WITHHELD")
        if (!fresh()) return alternatives.also { observe("PASSIVE_DIRECTION_STALE") }
        first = if (applyAdvice) proposal else alternatives.first()
        stamp = solveStamp
        changed = differs
        return if (applyAdvice && differs) alternatives.reversed() else alternatives
    }

    fun commit(decision: SearchDecision, solveStamp: Int, fresh: () -> Boolean) {
        val expected = first ?: return
        charge(4L)
        observe("PASSIVE_DIRECTION_COMMIT_CHECK")
        val accepted = decision == expected && stamp == solveStamp && fresh()
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe(if (applyAdvice) "PASSIVE_DIRECTION_COMMITTED" else "PASSIVE_DIRECTION_COMMIT_WITHHELD")
        if (wasChanged) observe(if (applyAdvice) "PASSIVE_DIRECTION_COMMIT_CHANGED"
            else "PASSIVE_DIRECTION_COMMIT_WITHHELD_CHANGED")
    }
}
