package com.eignex.klause.factor

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

// presents is a parallel array of presence literals; empty = every entry unconditionally present.
// isDefinitelyPresent/Absent use pinned propagation state (conservative); isPresentInAssignment
// uses the full LS assignment (complete). Propagators must use the pinned forms to stay sound.
internal object OptPresence {

    fun isPresentInAssignment(presents: IntArray, idx: Int, state: LocalSearchState): Boolean {
        if (presents.isEmpty()) return true
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.assignment.boolValue(v)
        return if (Lit.isPositive(lit)) raw else !raw
    }

    fun isDefinitelyPresent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return true
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) raw else !raw
    }

    /**
     * [base] extended by the presence premises a deduction over [tasks] (every task when null) rests on: a present
     * task's literal for the tasks it used, an absent one's for the tasks it skipped. Without them the reason holds
     * only while those presences stand, so a clause learned through it prunes schedules where they do not.
     */
    fun withPresencePremises(
        presents: IntArray,
        state: PropagationState,
        base: IntArray?,
        tasks: IntArray? = null,
    ): IntArray? {
        if (presents.isEmpty()) return base
        val out = IntArrayList()
        base?.forEach { out.add(it) }
        for (i in tasks ?: IntArray(presents.size) { it }) {
            when {
                isDefinitelyPresent(presents, i, state) -> out.add(Lit.negate(presents[i]))
                isDefinitelyAbsent(presents, i, state) -> out.add(presents[i])
            }
        }
        return if (out.size == 0) null else out.toIntArray()
    }

    fun isDefinitelyAbsent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return false
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) !raw else raw
    }

    // Factor constructors call this to extend boolVars so the propagation engine wakes on presence changes.
    // Deduplicated in first-occurrence order: boolVars is a variable *scope*, and positions sharing one
    // presence variable would otherwise enter every occurrence list once per position and be flipped once
    // per position by the moves that walk a factor's scope (an even count cancelling out to no move).
    fun presenceVarIds(presents: IntArray): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val seen = IntHashSet(presents.size)
        val out = IntArrayList(presents.size)
        for (lit in presents) {
            val v = Lit.variable(lit)
            if (seen.add(v)) out.add(v)
        }
        return out.toIntArray()
    }
}
