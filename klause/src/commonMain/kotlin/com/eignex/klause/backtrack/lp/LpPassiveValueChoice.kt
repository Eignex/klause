package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpPassiveValueChoice(
    private val applyAdvice: Boolean,
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
) {
    private var nomination: SearchDecision? = null
    private var nominationStamp = -1
    private var changed = false

    val hasNomination: Boolean get() = nomination != null

    fun clear() {
        nomination = null
        changed = false
    }

    fun select(
        variable: VarRef,
        point: Double,
        alternatives: List<SearchDecision>,
        preferUp: Boolean?,
        stamp: Int,
        fresh: () -> Boolean,
    ): List<SearchDecision> {
        clear()
        if (alternatives.size != 2 || preferUp == null) return alternatives
        charge(16L)
        observe("PASSIVE_VALUE_CHECK")
        if (!fresh()) return alternatives.also { observe("PASSIVE_VALUE_STALE") }
        if (!point.isFinite() || (variable is VarRef.Bool && point !in 0.0..1.0)) return alternatives
        val integral = floor(point)
        val fraction = point - integral
        if (fraction <= POINT_TOLERANCE || fraction >= 1.0 - POINT_TOLERANCE) return alternatives
        val down: SearchDecision
        val up: SearchDecision
        when (variable) {
            is VarRef.Bool -> {
                down = SearchDecision.Bool((variable.varId shl 1) or 1)
                up = SearchDecision.Bool(variable.varId shl 1)
            }
            is VarRef.IntVar -> {
                down = SearchDecision.IntAtMost(variable.varId, integral.toLong())
                up = SearchDecision.IntAtLeast(variable.varId, integral.toLong() + 1L)
            }
        }
        if (alternatives != listOf(down, up) && alternatives != listOf(up, down)) return alternatives
        if (!fresh()) return alternatives.also { observe("PASSIVE_VALUE_STALE") }
        val proposal = if (preferUp) up else down
        changed = proposal != alternatives.first()
        nomination = if (applyAdvice) proposal else alternatives.first()
        nominationStamp = stamp
        observe("PASSIVE_VALUE_COMPARED")
        observe(if (changed) "PASSIVE_VALUE_PROPOSAL_CHANGED" else "PASSIVE_VALUE_PROPOSAL_AGREED")
        observe(if (applyAdvice) "PASSIVE_VALUE_CONSUMED" else "PASSIVE_VALUE_WITHHELD")
        if (!fresh()) return alternatives.also {
            clear()
            observe("PASSIVE_VALUE_STALE")
        }
        return if (applyAdvice && changed) alternatives.reversed() else alternatives
    }

    fun commit(decision: SearchDecision, stamp: Int, fresh: () -> Boolean) {
        val expected = nomination ?: return
        charge(4L)
        observe("PASSIVE_VALUE_COMMIT_CHECK")
        val accepted = expected == decision && nominationStamp == stamp && fresh()
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe(if (applyAdvice) "PASSIVE_VALUE_COMMITTED" else "PASSIVE_VALUE_COMMIT_WITHHELD")
        if (wasChanged) observe(if (applyAdvice) "PASSIVE_VALUE_COMMIT_CHANGED"
        else "PASSIVE_VALUE_COMMIT_WITHHELD_CHANGED")
    }

    private companion object {
        const val POINT_TOLERANCE = 1e-6
    }
}
