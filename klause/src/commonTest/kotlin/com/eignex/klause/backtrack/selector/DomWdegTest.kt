package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DomWdegTest {

    @Test
    fun `dom-wdeg branches a column whose bounds span more than a Long can count`() {
        // An unbounded `var int` reaches the search with the full Long range. A domain magnitude that
        // wrapped to zero would read as fixed, and the column would never be selected — leaving the
        // engine to call a node complete with the column still open.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MAX_VALUE)),
            factors = arrayOf<Factor>(),
        )
        val session = PropagationSession(problem)

        assertEquals(VarRef.IntVar(0), DomWdeg().pick(session, Random(1)))
    }
}
