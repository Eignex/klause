package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

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

    fun isDefinitelyAbsent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return false
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) !raw else raw
    }

    // Factor constructors call this to extend boolVars so the propagation engine wakes on presence changes.
    fun presenceVarIds(presents: IntArray): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val out = IntArray(presents.size)
        for (i in presents.indices) out[i] = Lit.variable(presents[i])
        return out
    }
}
