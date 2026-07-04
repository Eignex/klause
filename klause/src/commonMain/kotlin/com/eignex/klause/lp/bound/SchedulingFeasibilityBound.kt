package com.eignex.klause.lp.bound

import com.eignex.klause.propagation.PropagationSession

/**
 * A scheduling-feasibility relaxation bound: detects that the current node's live domains cannot
 * pack a feasible schedule, and (on a prune) explains the infeasibility as a conflict clause.
 * Implemented by [CumulativeEnergeticBound] (energetic reasoning) and [CumulativeFlowBound]; the
 * backtrack LP prune cascade drives both through one gated arm.
 */
internal interface SchedulingFeasibilityBound {
    /** True iff the live domains prove no feasible schedule exists at this node. */
    fun isInfeasible(session: PropagationSession): Boolean

    /** Conflict-clause literals explaining the infeasibility for LP-nogood learning, or null. */
    fun explain(session: PropagationSession): IntArray?
}
