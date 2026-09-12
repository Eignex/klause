package com.eignex.klause.backtrack

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InprocessingLpEpochTest {
    @Test
    fun `epoch loop needs an explicit option an engine and an unseeded root`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 4)), emptyArray())
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "epochs"),
        ).use { engine ->
            assertFalse(BacktrackParams().lpEpochs)
            assertNull(Inprocessing.from(BacktrackParams(), engine))
            assertNull(Inprocessing.from(BacktrackParams(lpEpochs = true)))
            assertNull(Inprocessing.from(BacktrackParams(lpEpochs = true, assumptions = Assumptions.None.withInt(0, 1)), engine))
            assertNotNull(Inprocessing.from(BacktrackParams(lpEpochs = true), engine))
        }
    }

}
