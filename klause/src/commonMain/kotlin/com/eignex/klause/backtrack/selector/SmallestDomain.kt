package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

/**
 * First-fail: smallest current domain wins. Unpinned bools count as size 2. Ties broken by variable id,
 * bools before ints.
 */
object SmallestDomain : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = false, boolScore = 2L) { it.valueCount }
}
