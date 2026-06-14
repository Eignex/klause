package com.eignex.klause.solver.backtrack.selector

import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random

/**
 * "First-fail": smallest current domain wins. Bools count as size 2 when unpinned. Tied
 * candidates are broken by variable id (bools precede ints). The classic CSP default.
 */
object SmallestDomain : VariableSelector {
    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = false, boolScore = 2) { it.size }
}
