package com.eignex.klause.bound

import com.eignex.klause.propagation.PropagationSession

// Proves the live domains admit no schedule and explains it as a conflict clause for LP-nogood learning.
internal interface SchedulingFeasibilityBound {
    fun isInfeasible(session: PropagationSession): Boolean

    fun explain(session: PropagationSession): IntArray?
}
