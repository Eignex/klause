package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * Smallest lower bound first (MiniZinc's `smallest`). Free bools count as minimum 0. Ties broken by variable
 * id, bools before ints.
 */
object SmallestLowerBound : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = false, boolScore = 0L) { it.min }
}
