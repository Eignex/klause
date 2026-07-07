package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class IndomainMedianTest {

    private val rng = Random(1)

    @Test
    fun `indomain_median is the middle by position - distinct from indomain_middle mean of bounds`() {
        // Domain {0,1,2,3,10}: median by position is valueAt(2) = 2; the mean of bounds is 5,
        // whose nearest present value is 3. So the two heuristics start on different values.
        var d = IntDomain(0, 10)
        for (v in 4..9) d = d.excludeValue(v.toLong())
        val problem = Problem(0, 1, arrayOf(d), arrayOf<Factor>())
        val session = PropagationSession(problem)
        assertEquals(2L, IndomainMedian.values(session, VarRef.IntVar(0), rng).first())
        assertEquals(3L, IndomainMiddle.values(session, VarRef.IntVar(0), rng).first())
    }
}
