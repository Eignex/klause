package com.eignex.klause.backtrack.lp

import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.math.floor

internal class LpPassiveVariableChoice(
    private val applyAdvice: Boolean,
    private val observe: (String) -> Unit,
    private val charge: (Long) -> Unit,
    private val kind: Kind = Kind.RELIABLE,
) {
    enum class Kind(prefix: String) {
        RELIABLE("PASSIVE_VARIABLE_"),
        MEASURED("CURRENT_VARIABLE_"),
        COLLECTION("PASSIVE_COLLECTION_VARIABLE_");

        val selection = prefix + "SELECTION"
        val stale = prefix + "STALE"
        val unreliable = prefix + "UNRELIABLE"
        val compared = prefix + "COMPARED"
        val proposalChanged = prefix + "PROPOSAL_CHANGED"
        val proposalAgreed = prefix + "PROPOSAL_AGREED"
        val consumed = prefix + "CONSUMED"
        val withheld = prefix + "WITHHELD"
        val branchCheck = prefix + "BRANCH_CHECK"
        val commitCheck = prefix + "COMMIT_CHECK"
        val committed = prefix + "COMMITTED"
        val commitWithheld = prefix + "COMMIT_WITHHELD"
        val commitChanged = prefix + "COMMIT_CHANGED"
        val commitWithheldChanged = prefix + "COMMIT_WITHHELD_CHANGED"
    }

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
        observe(kind.selection)
        charge(16L)
        if (!fresh()) return baseline.variable.also { observe(kind.stale) }
        if (!proposal.fractional() || (kind != Kind.COLLECTION && !baseline.fractional()) || !reliable()) {
            observe(kind.unreliable)
            return baseline.variable
        }
        if (!fresh()) return baseline.variable.also { observe(kind.stale) }
        changed = proposal.variable != baseline.variable
        observe(kind.compared)
        observe(if (changed) kind.proposalChanged else kind.proposalAgreed)
        observe(if (applyAdvice) kind.consumed else kind.withheld)
        nomination = if (applyAdvice) proposal else baseline
        nominationStamp = stamp
        return checkNotNull(nomination).variable
    }

    fun branch(variable: VarRef, stamp: Int) {
        if (nomination == null) return
        observe(kind.branchCheck)
        charge(4L)
        branched = nomination?.variable == variable && nominationStamp == stamp
        if (!branched) clear()
    }

    fun commit(decision: SearchDecision) {
        val current = nomination ?: return
        observe(kind.commitCheck)
        charge(4L)
        val accepted = branched && current.matches(decision)
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe(if (applyAdvice) kind.committed else kind.commitWithheld)
        if (wasChanged) observe(if (applyAdvice) kind.commitChanged
            else kind.commitWithheldChanged)
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
