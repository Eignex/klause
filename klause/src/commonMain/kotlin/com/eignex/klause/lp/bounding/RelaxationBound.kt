package com.eignex.klause.lp.bounding

import com.eignex.klause.propagation.PropagationSession

// prune performs the source's own gating and side effects and returns true when it proves the node
// dominated or infeasible.
internal interface RelaxationBound {
    val applicable: Boolean

    fun prune(
        session: PropagationSession,
        effectiveBound: Double,
        objectiveVar: Int,
        objectiveAscending: Boolean,
    ): Boolean
}
