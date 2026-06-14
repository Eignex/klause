package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * Smallest lower bound first (MiniZinc's `smallest`): the free variable whose domain
 * minimum is lowest. Free bools count as minimum 0. Ties broken by variable id, bools
 * before ints. The scheduling staple — branching on the task that can start earliest.
 */
object SmallestLowerBound : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = false, boolScore = 0) { it.min }
}
