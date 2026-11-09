package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpPassiveDirectionChoice(
    private val applyAdvice: Boolean,
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
    private val kind: Kind = Kind.ADVICE,
) {
    enum class Kind(prefix: String) {
        ADVICE("PASSIVE_DIRECTION"), EXPLORATION("PASSIVE_EXPLORATION");

        val selection = "${prefix}_SELECTION"
        val stale = "${prefix}_STALE"
        val compared = "${prefix}_COMPARED"
        val proposalChanged = "${prefix}_PROPOSAL_CHANGED"
        val proposalAgreed = "${prefix}_PROPOSAL_AGREED"
        val consumed = "${prefix}_CONSUMED"
        val withheld = "${prefix}_WITHHELD"
        val commitCheck = "${prefix}_COMMIT_CHECK"
        val committed = "${prefix}_COMMITTED"
        val commitChanged = "${prefix}_COMMIT_CHANGED"
        val commitWithheld = "${prefix}_COMMIT_WITHHELD"
        val commitWithheldChanged = "${prefix}_COMMIT_WITHHELD_CHANGED"
    }

    private var first: SearchDecision? = null
    private var stamp = -1
    private var changed = false

    val hasNomination: Boolean get() = first != null

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
        observe(kind.selection)
        if (!fresh()) return alternatives.also { observe(kind.stale) }
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
        if (!fresh()) return alternatives.also { observe(kind.stale) }
        observe(kind.compared)
        observe(if (differs) kind.proposalChanged else kind.proposalAgreed)
        observe(if (applyAdvice) kind.consumed else kind.withheld)
        if (!fresh()) return alternatives.also { observe(kind.stale) }
        first = if (applyAdvice) proposal else alternatives.first()
        stamp = solveStamp
        changed = differs
        return if (applyAdvice && differs) alternatives.reversed() else alternatives
    }

    fun commit(decision: SearchDecision, solveStamp: Int, fresh: () -> Boolean) {
        val expected = first ?: return
        charge(4L)
        observe(kind.commitCheck)
        val accepted = decision == expected && stamp == solveStamp && fresh()
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe(if (applyAdvice) kind.committed else kind.commitWithheld)
        if (wasChanged) observe(if (applyAdvice) kind.commitChanged else kind.commitWithheldChanged)
    }
}
