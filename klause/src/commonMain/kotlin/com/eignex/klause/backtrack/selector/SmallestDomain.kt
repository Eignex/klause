package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import kotlin.random.Random

/**
 * "First-fail": smallest current domain wins. Bools count as size 2 when unpinned. Tied
 * candidates are broken by variable id (bools precede ints). The classic CSP default.
 */
object SmallestDomain : VariableSelector {
    override fun fresh() = this

    override fun pick(session: PropagationSession, rng: Random): VarRef? =
        pickByDomainMetric(session, maximize = false, boolScore = 2L) { it.sizeLong }
}
