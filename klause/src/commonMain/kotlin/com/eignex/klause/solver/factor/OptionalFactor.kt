package com.eignex.klause.solver.factor

import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationState

// Mixin for optional-element factors: centralises per-element presence checks so each factor
// doesn't carry a private present() wrapper or reach into OptPresence directly.
internal interface OptionalFactor {
    // Parallel presence literals; empty when every element is mandatory (non-opt fast path).
    val presents: IntArray

    fun present(state: LocalSearchState, idx: Int): Boolean = OptPresence.isPresentInAssignment(presents, idx, state)

    fun definitelyPresent(idx: Int, state: PropagationState): Boolean =
        OptPresence.isDefinitelyPresent(presents, idx, state)

    fun definitelyAbsent(idx: Int, state: PropagationState): Boolean =
        OptPresence.isDefinitelyAbsent(presents, idx, state)
}
