package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LpBranchTest {
    @Test
    fun `directional history preserves the reduced cost variable choice`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, emptyArray<Factor>())
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 2, cost = 1) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
        val hints = LpHints(2, 0, directionalGains = true)
        repeat(2) {
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 1.25), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.branch(VarRef.IntVar(1))
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(1, 2) else SearchDecision.IntAtMost(1, 1), 1)
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, if (up) 2.0 else 1.0), DoubleArray(0))
                hints.recordBound(relaxation, if (up) 13.0 else 11.0)
            }
        }
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(0.5, 1.25), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink("directional-variable-choice")).use { engine ->
            val selected = engine.lpBranchPick(PropagationSession(problem), hints)

            assertEquals(VarRef.IntVar(0), selected)
        }
    }
}
