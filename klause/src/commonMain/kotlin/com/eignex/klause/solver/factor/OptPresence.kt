package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Helpers for factor-native handling of optional variables. Every opt-aware factor stores a
 * `presents: IntArray` of *literals* (constructed via [Lit.make]) running parallel to its
 * value-side index space. An empty array means "no opt info — every entry is unconditionally
 * present" (the non-opt fast path).
 *
 * Semantics:
 *
 *  - [isPresentInAssignment]: read-out under a complete LS assignment. Returns `true` when
 *    the presence literal evaluates to true (or the array is empty). Used by the LS-cost
 *    hooks (`initialize` / `isViolated` / `delta*` / `apply*`) which only see complete
 *    assignments.
 *  - [isDefinitelyPresent]: read-out under a partial assignment during propagation. Returns
 *    `true` only when the presence literal is pinned to true. Unpinned counts as "may yet
 *    become absent" — propagators that use it for filtering stay sound.
 *  - [isDefinitelyAbsent]: read-out under a partial assignment during propagation. Returns
 *    `true` when the presence literal is pinned to false; index can be skipped entirely.
 *
 * The empty-array fast path means non-opt construction sites need no migration — factors keep
 * their current behavior bit-for-bit by defaulting `presents` to `EmptyIntArray`.
 */
internal object OptPresence {

    /** True if entry [idx] is present under the current full LS assignment, or [presents] is
     *  empty (non-opt factor). */
    fun isPresentInAssignment(presents: IntArray, idx: Int, state: LocalSearchState): Boolean {
        if (presents.isEmpty()) return true
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.assignment.boolValue(v)
        return if (Lit.isPositive(lit)) raw else !raw
    }

    /** True only if the presence literal for entry [idx] is *pinned true* (or [presents] is
     *  empty). Used by propagators that need a sound under-approximation of "present". */
    fun isDefinitelyPresent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return true
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) raw else !raw
    }

    /** True only if the presence literal for entry [idx] is *pinned false*. When this is true
     *  the index contributes nothing and can be skipped without losing soundness. */
    fun isDefinitelyAbsent(presents: IntArray, idx: Int, state: PropagationState): Boolean {
        if (presents.isEmpty()) return false
        val lit = presents[idx]
        val v = Lit.variable(lit)
        val raw = state.boolValues[v] ?: return false
        return if (Lit.isPositive(lit)) !raw else raw
    }

    /** Bool-var ids carried in [presents] (positive or negative literal — variable id only).
     *  Used by factor constructors to extend `boolVars` so the propagation engine wakes the
     *  factor on presence changes. Returns the shared empty array when [presents] is empty. */
    fun presenceVarIds(presents: IntArray): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val out = IntArray(presents.size)
        for (i in presents.indices) out[i] = Lit.variable(presents[i])
        return out
    }
}
