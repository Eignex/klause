package com.eignex.klause.backtrack.lp

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
    fun `directional reranking stays within the two strongest open LP candidates`() {
        val cases = listOf(
            Triple(false, false, false), Triple(false, false, true),
            Triple(false, true, false), Triple(false, true, true),
            Triple(true, false, false), Triple(true, false, true),
            Triple(true, true, false), Triple(true, true, true),
        )
        for ((boolean, fixedFirst, observedSecond) in cases) {
            val costs = longArrayOf(10, 9, 1)
            val problem = Problem(
                numBoolVars = if (boolean) 3 else 0,
                numIntVars = if (boolean) 0 else 3,
                intDomains = Array(if (boolean) 0 else 3) { IntDomain(0, 10) },
                factors = emptyArray(),
            )
            val objective = if (boolean) LinearObjective(boolWeights = costs)
            else LinearObjective(intCoefficients = costs)
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, if (boolean) 1 else 10, cost = costs[it]) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(
                assertNotNull(trail.state.ownerWorkingModel()),
                intArrayOf(0, 1, 2), BooleanArray(3) { boolean }, 0L,
                if (boolean) IntArray(0) else intArrayOf(0, 1, 2),
                if (boolean) intArrayOf(0, 1, 2) else IntArray(0),
            )
            val variables: List<VarRef> = List(3) { if (boolean) VarRef.Bool(it) else VarRef.IntVar(it) }
            val hints = LpHints(problem.numIntVars, problem.numBoolVars, directionalGains = true)
            val current = if (boolean) 0.25 else 2.25
            for (index in if (observedSecond) listOf(2, 1) else listOf(2)) {
                val lower = if (boolean) SearchDecision.Bool(index * 2 + 1)
                else SearchDecision.IntAtMost(index, 2)
                val upper = if (boolean) SearchDecision.Bool(index * 2)
                else SearchDecision.IntAtLeast(index, 3)
                repeat(2) {
                    hints.beginNode(0, 100.0)
                    hints.record(relaxation, DoubleArray(3) { current }, DoubleArray(0))
                    hints.recordObjective(relaxation, 10.0)
                    hints.branch(variables[index])
                    for (up in listOf(false, true)) {
                        hints.commit(if (up) upper else lower, 1)
                        hints.beginNode(1, 100.0)
                        val gain = if (up) 3.0 else 1.0
                        val multiplier = if (index == 2) 10.0 else 1.0
                        hints.recordObjective(relaxation, 10.0 + gain * multiplier)
                    }
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, DoubleArray(3) { current }, DoubleArray(0))
            hints.recordObjective(relaxation, 10.0)
            val session = PropagationSession(problem)
            if (fixedFirst) {
                if (boolean) session.pinBool(0, false) else session.pinInt(0, 2)
            }

            val selected = LpEngine(
                problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("shortlist"),
            ).use { engine -> engine.lpBranchPick(session, hints) }

            val expected = when {
                fixedFirst -> variables[2]
                observedSecond -> variables[1]
                else -> variables[0]
            }
            assertEquals(expected, selected)
        }
    }
}
