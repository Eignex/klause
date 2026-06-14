package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/** Largest upper bound first (MiniZinc's `largest`). Free bools count as maximum 1. */
object LargestUpperBound : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = true, boolScore = 1) { it.max }
}
