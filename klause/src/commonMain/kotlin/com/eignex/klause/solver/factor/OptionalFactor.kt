package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Mixin for global factors whose elements can be *optional* — each element carries a presence
 * literal and contributes to the constraint only while present. Centralises the per-element
 * presence checks the optional-aware globals (AllDifferent, GlobalCardinality, Cumulative,
 * NValue) all share, so they no longer each carry a private `present()` wrapper or reach into
 * [OptPresence] directly.
 *
 * [presents] is empty on the non-opt fast path (every element mandatory); the default methods
 * then report every element present / never definitely-absent, matching the all-mandatory
 * semantics of [OptPresence].
 */
internal interface OptionalFactor {
    /** Per-element presence literals, parallel to the factor's element array; empty when every
     *  element is mandatory (the non-opt fast path). */
    val presents: IntArray

    /** True iff element [idx] is present under the current local-search assignment. */
    fun present(state: LocalSearchState, idx: Int): Boolean = OptPresence.isPresentInAssignment(presents, idx, state)

    /** True iff element [idx] is fixed present in the current propagation state. */
    fun definitelyPresent(idx: Int, state: PropagationState): Boolean =
        OptPresence.isDefinitelyPresent(presents, idx, state)

    /** True iff element [idx] is fixed absent in the current propagation state. */
    fun definitelyAbsent(idx: Int, state: PropagationState): Boolean =
        OptPresence.isDefinitelyAbsent(presents, idx, state)
}
