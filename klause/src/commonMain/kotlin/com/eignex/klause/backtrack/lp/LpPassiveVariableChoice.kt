package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpPassiveVariableChoice(
    private val applyAdvice: Boolean,
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
) {
    data class Candidate(val variable: VarRef, val point: Double)

    private var nomination: Candidate? = null
    private var nominationStamp = -1
    private var changed = false
    private var branched = false

    val nominatedVariable: VarRef? get() = nomination?.variable

    fun clear() {
        if (nomination == null && !changed && !branched) return
        nomination = null
        changed = false
        branched = false
    }

    fun select(
        proposal: Candidate,
        baseline: Candidate,
        stamp: Int,
        fresh: () -> Boolean,
        reliable: () -> Boolean,
    ): VarRef {
        clear()
        observe("PASSIVE_VARIABLE_SELECTION")
        charge(16L)
        if (!fresh()) return baseline.variable.also { observe("PASSIVE_VARIABLE_STALE") }
        if (!proposal.fractional() || !baseline.fractional() || !reliable()) {
            observe("PASSIVE_VARIABLE_UNRELIABLE")
            return baseline.variable
        }
        if (!fresh()) return baseline.variable.also { observe("PASSIVE_VARIABLE_STALE") }
        changed = proposal.variable != baseline.variable
        observe("PASSIVE_VARIABLE_COMPARED")
        observe(if (changed) "PASSIVE_VARIABLE_PROPOSAL_CHANGED" else "PASSIVE_VARIABLE_PROPOSAL_AGREED")
        observe(if (applyAdvice) "PASSIVE_VARIABLE_CONSUMED" else "PASSIVE_VARIABLE_WITHHELD")
        nomination = if (applyAdvice) proposal else baseline
        nominationStamp = stamp
        return checkNotNull(nomination).variable
    }

    fun branch(variable: VarRef, stamp: Int) {
        if (nomination == null) return
        observe("PASSIVE_VARIABLE_BRANCH_CHECK")
        charge(4L)
        branched = nomination?.variable == variable && nominationStamp == stamp
        if (!branched) clear()
    }

    fun commit(decision: SearchDecision) {
        val current = nomination ?: return
        observe("PASSIVE_VARIABLE_COMMIT_CHECK")
        charge(4L)
        val accepted = branched && current.matches(decision)
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe(if (applyAdvice) "PASSIVE_VARIABLE_COMMITTED" else "PASSIVE_VARIABLE_COMMIT_WITHHELD")
        if (wasChanged) observe(if (applyAdvice) "PASSIVE_VARIABLE_COMMIT_CHANGED"
            else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED")
    }

    private fun Candidate.fractional(): Boolean {
        val fraction = point - floor(point)
        return point.isFinite() && fraction > POINT_TOLERANCE && fraction < 1.0 - POINT_TOLERANCE &&
            (variable !is VarRef.Bool || point in 0.0..1.0)
    }

    private fun Candidate.matches(decision: SearchDecision): Boolean = when (decision) {
        is SearchDecision.Bool -> variable is VarRef.Bool && variable.varId == (decision.literal ushr 1)
        is SearchDecision.IntAtMost -> variable is VarRef.IntVar && variable.varId == decision.variable &&
            decision.upper == floor(point).toLong()
        is SearchDecision.IntAtLeast -> variable is VarRef.IntVar && variable.varId == decision.variable &&
            decision.lower == floor(point).toLong() + 1L
        else -> false
    }

    private companion object {
        const val POINT_TOLERANCE = 1e-6
    }
}
