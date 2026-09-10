package com.eignex.klause.lp.bounding

import com.eignex.klause.propagation.PropagationSession

/** One relaxation-bound source in the per-node prune cascade. [prune] performs the source's own
 *  gating + side effects (telemetry, nogood/backjump recording, state updates) and returns true
 *  when it proves the node dominated/infeasible. Sources are tried in list order; the first true
 *  short-circuits. */
internal interface RelaxationBound {
    val applicable: Boolean

    fun prune(
        session: PropagationSession,
        effectiveBound: Double,
        objectiveVar: Int,
        objectiveAscending: Boolean,
    ): Boolean
}
