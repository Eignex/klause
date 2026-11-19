package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpBranchTest {
    @Test
    fun `branch selection spends the shared LP work allowance`() {
        val problem = Problem(0, 8192, Array(8192) { IntDomain(0, 1) }, emptyArray())
        val builder = LpBuilder()
        builder.addVar(0, 1, cost = 1L)
        val model = builder.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L,
            IntArray(8192) { if (it == 0) 0 else -1 }, IntArray(0))
        val hints = LpHints(8192, 0)
        hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
        LpEngine(problem, LinearObjective(intCoefficients = LongArray(8192) { 1L }),
            LpParams(lpPlan = LpPlan(bounding = true), solveBudgetMillis = 1L),
            SolveStatsSink("branch-guidance-work")).use { engine ->
            assertFalse(engine.lpWorkAllowanceSpent())

            engine.lpBranchPick(PropagationSession(problem), hints)

            assertTrue(engine.lpWorkAllowanceSpent())
        }
    }
}
