package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpPassiveCollection(
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
    maxChecks: Int = 128,
    maxSelections: Int = 32,
) {
    private var remainingChecks = maxChecks
    private var remainingSelections = maxSelections
    private var nomination: SearchDecision? = null
    private var nominationStamp = -1
    private var changed = false

    init {
        require(maxChecks in 0..128 && maxSelections in 0..32)
    }

    val available: Boolean get() = remainingChecks > 0 && remainingSelections > 0
    val hasNomination: Boolean get() = nomination != null

    fun clear() {
        nomination = null
        changed = false
    }

    fun select(
        variable: VarRef,
        point: Double,
        alternatives: List<SearchDecision>,
        history: LpDirectionalHistory,
        stamp: Int,
        fresh: () -> Boolean,
    ): List<SearchDecision> {
        clear()
        if (!available) return alternatives
        remainingChecks--
        charge(16L)
        observe("PASSIVE_COLLECTION_CHECK")
        if (!fresh()) return alternatives.also { observe("PASSIVE_COLLECTION_STALE") }
        val preferUp = history.preferUnderObservedDirection(variable, point)
            ?: return alternatives.also { observe("PASSIVE_COLLECTION_NO_PROPOSAL") }
        val down: SearchDecision
        val up: SearchDecision
        when (variable) {
            is VarRef.Bool -> {
                down = SearchDecision.Bool((variable.varId shl 1) or 1)
                up = SearchDecision.Bool(variable.varId shl 1)
            }
            is VarRef.IntVar -> {
                down = SearchDecision.IntAtMost(variable.varId, floor(point).toLong())
                up = SearchDecision.IntAtLeast(variable.varId, floor(point).toLong() + 1L)
            }
        }
        if (alternatives != listOf(down, up) && alternatives != listOf(up, down)) return alternatives
        remainingSelections--
        charge(16L)
        if (!fresh()) return alternatives.also { observe("PASSIVE_COLLECTION_STALE") }
        val proposal = if (preferUp) up else down
        val differs = alternatives.first() != proposal
        observe("PASSIVE_COLLECTION_COMPARED")
        observe(if (differs) "PASSIVE_COLLECTION_PROPOSAL_CHANGED" else "PASSIVE_COLLECTION_PROPOSAL_AGREED")
        if (!fresh()) return alternatives.also { observe("PASSIVE_COLLECTION_STALE") }
        nomination = proposal
        nominationStamp = stamp
        changed = differs
        return if (differs) alternatives.reversed() else alternatives
    }

    internal inline fun commit(decision: SearchDecision, stamp: Int, crossinline fresh: () -> Boolean) {
        val expected = nomination ?: return
        charge(4L)
        observe("PASSIVE_COLLECTION_COMMIT_CHECK")
        val accepted = expected == decision && nominationStamp == stamp && fresh()
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe("PASSIVE_COLLECTION_COMMITTED")
        if (wasChanged) observe("PASSIVE_COLLECTION_COMMIT_CHANGED")
    }
}
