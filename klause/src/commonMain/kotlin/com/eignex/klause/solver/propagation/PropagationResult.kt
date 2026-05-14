package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.propagation.PropagationResult

/**
 * Result of [com.eignex.klause.solver.Problem.propagate]. Either a (possibly empty) set of
 * literals/values forced beyond the input assumptions, or a sound (but incomplete) proof of
 * infeasibility.
 */
sealed interface PropagationResult {
    /** [bools] and [ints] are disjoint from the input assumptions: only newly-forced facts. */
    data class Implied(val bools: Map<Int, Boolean>, val ints: Map<Int, Int>) : PropagationResult {
        val isEmpty: Boolean get() = bools.isEmpty() && ints.isEmpty()
    }

    /**
     * Sound, incomplete proof of infeasibility.
     *
     *  - [conflictLevels] is the set of *decision levels* involved in the conflict. For a
     *    [PropagationSession], `session.pinBool(v, value)` lives at the level it was pushed
     *    at; `seed` assigns levels `1..|assumptions|` in iteration order. Level 0 is never
     *    in the set — it represents the problem-constraint phase, not a decision.
     *  - [conflictBools] / [conflictInts] are the decision variables at those levels. They
     *    are derived from [conflictLevels] for convenience; CSP-style DFS samplers typically
     *    read [conflictLevels] directly to compute their backjump target.
     *
     *  The conflict subset is jointly unsatisfiable but not guaranteed minimal — callers must
     *  not assume minimality. An empty result means the contradiction was implied by problem
     *  constraints alone (no input was load-bearing).
     */
    data class Unsat(
        val conflictBools: Set<Int> = emptySet(),
        val conflictInts: Set<Int> = emptySet(),
        val conflictLevels: Set<Int> = emptySet(),
    ) : PropagationResult
}
