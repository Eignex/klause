package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntInputOrderTest {
    @Test
    fun `picks the first unfixed integer before booleans`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 3), IntDomain(0, 1)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)

        val picked = IntInputOrder.pick(session, Random(0L))

        assertEquals(VarRef.IntVar(1), picked)
    }

    @Test
    fun `picks the first unfixed boolean when integers are fixed`() {
        for (intCount in listOf(0, 2)) {
            val problem = Problem(
                numBoolVars = 2,
                numIntVars = intCount,
                intDomains = Array(intCount) { IntDomain(0, 0) },
                factors = emptyArray(),
            )
            val session = PropagationSession(problem)
            session.seed(Assumptions(bools = mapOf(0 to true)))

            val picked = IntInputOrder.pick(session, Random(0L))

            assertEquals(VarRef.Bool(1), picked)
        }
    }

    @Test
    fun `returns null when all variables are fixed`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(1, 1)),
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        session.seed(Assumptions(bools = mapOf(0 to true)))

        val picked = IntInputOrder.pick(session, Random(0L))

        assertNull(picked)
    }
}
