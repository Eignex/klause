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
    private var partition: List<SearchDecision>? = null

    val nominatedVariable: VarRef? get() = nomination?.variable

    fun clear() {
        if (nomination == null && !changed && !branched) return
        nomination = null
        changed = false
        branched = false
        partition = null
    }

    internal inline fun select(
        proposal: Candidate,
        baseline: Candidate,
        stamp: Int,
        crossinline fresh: () -> Boolean,
        crossinline reliable: () -> Boolean,
    ): VarRef {
        clear()
        observe("PASSIVE_VARIABLE_SELECTION")
        charge(16L)
        if (!fresh()) return baseline.variable.also { observe("PASSIVE_VARIABLE_STALE") }
        if (!proposal.fractional() || !baseline.validPoint() || !reliable()) {
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

    internal inline fun alternatives(
        variable: VarRef,
        alternatives: List<SearchDecision>,
        stamp: Int,
        crossinline fresh: () -> Boolean,
    ) {
        val current = nomination ?: return
        charge(8L)
        if (nomination !== current || !branched || variable != current.variable ||
            nominationStamp != stamp || !fresh() || !current.exhaustive(alternatives)
        ) {
            clear()
            return
        }
        partition = alternatives
    }

    internal inline fun commit(decision: SearchDecision, crossinline fresh: () -> Boolean) {
        if (nomination == null) return
        observe("PASSIVE_VARIABLE_COMMIT_CHECK")
        charge(4L)
        val accepted = branched && partition?.contains(decision) == true && fresh()
        val wasChanged = changed
        clear()
        if (!accepted) return
        observe(if (applyAdvice) "PASSIVE_VARIABLE_COMMITTED" else "PASSIVE_VARIABLE_COMMIT_WITHHELD")
        if (wasChanged) observe(if (applyAdvice) "PASSIVE_VARIABLE_COMMIT_CHANGED"
        else "PASSIVE_VARIABLE_COMMIT_WITHHELD_CHANGED")
    }

    private fun Candidate.fractional(): Boolean {
        val fraction = point - floor(point)
        return validPoint() && fraction > POINT_TOLERANCE && fraction < 1.0 - POINT_TOLERANCE
    }

    private fun Candidate.validPoint(): Boolean = point.isFinite() && when (variable) {
        is VarRef.Bool -> point in 0.0..1.0
        is VarRef.IntVar -> point >= Long.MIN_VALUE.toDouble() && point < Long.MAX_VALUE.toDouble()
    }

    private fun Candidate.matches(decision: SearchDecision): Boolean = when (decision) {
        is SearchDecision.Bool -> variable is VarRef.Bool && variable.varId == (decision.literal ushr 1)
        is SearchDecision.IntAtMost -> variable is VarRef.IntVar && variable.varId == decision.variable &&
            decision.upper == floor(point).toLong()
        is SearchDecision.IntAtLeast -> variable is VarRef.IntVar && variable.varId == decision.variable &&
            decision.lower == floor(point).toLong() + 1L
        else -> false
    }

    private fun Candidate.exhaustive(alternatives: List<SearchDecision>): Boolean {
        if (alternatives.size != 2) return false
        val first = alternatives[0]
        val second = alternatives[1]
        return when (variable) {
            is VarRef.Bool -> first is SearchDecision.Bool && second is SearchDecision.Bool &&
                first.literal ushr 1 == variable.varId && second.literal == (first.literal xor 1)
            is VarRef.IntVar -> {
                val down = (first as? SearchDecision.IntAtMost) ?: (second as? SearchDecision.IntAtMost)
                val up = (first as? SearchDecision.IntAtLeast) ?: (second as? SearchDecision.IntAtLeast)
                down != null && up != null && down.variable == variable.varId && up.variable == variable.varId &&
                    down.upper < Long.MAX_VALUE && up.lower == down.upper + 1L &&
                    ((!applyAdvice && point == floor(point)) || (matches(down) && matches(up)))
            }
        }
    }

    private companion object {
        const val POINT_TOLERANCE = 1e-6
    }
}
