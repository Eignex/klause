package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpMixedEpochTest {
    @Test
    fun `continuous CP rows decline the legacy bound adapter without changing domains`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 4)),
            arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(0.5), LinearOp.GE, 1.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(-2.0),
            realUpper = doubleArrayOf(2.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val relaxation = CpToLpRelaxation(problem, objective).build(RootDomains(problem))
        val session = PropagationSession(problem)
        val domain = session.intDomain(0)
        val stats = SolveStatsSink(backend = "mixed-epoch")
        LpEngine(problem, objective, LpParams(), stats).use { engine ->
            assertEquals("continuous_cp_rebind", LpEpochState.declineReason(relaxation))
            assertFalse(engine.rebuildEpoch(session, Cancellation.Never))
            assertNull(engine.epochState)
            assertEquals(domain, session.intDomain(0))
            assertTrue(relaxation.colRealId.any { it == 0 })
        }
    }
}
