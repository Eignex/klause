package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random

// Impact-based value selection (Refalo, "Impact-Based Search Strategies for Constraint Programming",
// CP 2004, https://doi.org/10.1007/978-3-540-30201-8_41): values in ascending order of the remaining
// search space after a probed pin; refuted values are dropped. Larger int domains probe a random subset of
// [maxProbes] and append the rest ascending, so coverage is preserved.
internal class Impact(private val maxProbes: Int = 32) : ValueSelector {

    override fun fresh() = Impact(maxProbes)
    override fun values(session: PropagationSession, varRef: VarRef, rng: Random): Sequence<Long> =
        probeAndOrder(session, varRef, rng, maxProbes, ascending = true)
}
