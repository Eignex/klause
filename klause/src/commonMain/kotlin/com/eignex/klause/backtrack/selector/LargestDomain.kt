package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.orderingSize
import kotlin.random.Random

/** Largest current domain. Useful as a contrast / for `solve` annotations that ask for it. */
object LargestDomain : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = true, boolScore = 2L) { it.orderingSize() }
}
