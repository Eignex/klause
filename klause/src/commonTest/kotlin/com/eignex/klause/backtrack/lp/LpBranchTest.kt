package com.eignex.klause.backtrack.lp

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
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
    fun `comparable reliable history can select beyond the reduced cost shortlist`() {
        for (mixed in listOf(false, true)) for (samples in 1..2) for (runnerStronger in listOf(false, true)) {
            for (trained in listOf(listOf(0), listOf(1), listOf(2), listOf(0, 1), listOf(0, 2))) {
                for (stale in listOf(false, true)) for (enabled in listOf(false, true)) {
                    val bools = if (mixed) 1 else 0
                    val problem = Problem(bools, 3 - bools, Array(3 - bools) { IntDomain(0, 2) }, emptyArray<Factor>())
                    val builder = LpBuilder()
                    repeat(3) { builder.addVar(0, if (mixed && it == 0) 1 else 2, cost = 1) }
                    val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                    val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()),
                        if (mixed) intArrayOf(0, 0, 1) else intArrayOf(0, 1, 2),
                        BooleanArray(3) { mixed && it == 0 }, 0L,
                        if (mixed) intArrayOf(1, 2) else intArrayOf(0, 1, 2),
                        if (mixed) intArrayOf(0) else IntArray(0))
                    val observations = ArrayList<String>()
                    val hints = LpHints(3 - bools, bools, directionalGains = enabled,
                        freshVariableProbes = true, observe = observations::add)
                    val point = doubleArrayOf(0.5, 1.25, 1.125)
                    repeat(samples) {
                        for (column in trained) {
                            val variable = if (mixed && column == 0) VarRef.Bool(0) else VarRef.IntVar(column - bools)
                            val lower = if (column == 0) 0L else 1L
                            hints.beginNode(0, 100.0)
                            hints.record(relaxation, point, DoubleArray(0))
                            hints.recordBound(relaxation, 10.0)
                            hints.branch(variable)
                            for (up in listOf(false, true)) {
                                val decision = if (variable is VarRef.Bool) {
                                    SearchDecision.Bool(Lit.make(variable.varId, up))
                                } else if (up) {
                                    SearchDecision.IntAtLeast((variable as VarRef.IntVar).varId, lower + 1L)
                                } else SearchDecision.IntAtMost((variable as VarRef.IntVar).varId, lower)
                                hints.commit(decision, 1)
                                hints.beginNode(1, 100.0)
                                val child = point.copyOf().also { it[column] = (lower + if (up) 1L else 0L).toDouble() }
                                hints.record(relaxation, child, DoubleArray(0))
                                val gain = if (up) 3.0 else 1.0
                                val scale = if (column > 0 && runnerStronger) 2 else 1
                                hints.recordBound(relaxation, 10.0 + gain * scale)
                            }
                        }
                    }
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, point, DoubleArray(0))
                    hints.recordBound(relaxation, 10.0)
                    hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(3) { VarStatus.AT_LOWER }))
                    if (stale) hints.beginNode(0, 100.0)
                    val objective = LinearObjective(intCoefficients = LongArray(3 - bools) { 1L },
                        boolWeights = LongArray(bools) { 1L })
                    LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
                        SolveStatsSink("directional-variable-choice")).use { engine ->
                        val selected = engine.lpBranchPick(PropagationSession(problem), hints)

                        val baseline = if (mixed) VarRef.Bool(0) else VarRef.IntVar(0)
                        val useRunner = samples == 2 && trained.size == 2 && runnerStronger && !stale && enabled
                        val expected = if (useRunner) {
                            VarRef.IntVar(trained.last() - bools)
                        } else baseline
                        assertEquals(expected, selected,
                            "mixed=$mixed samples=$samples trained=$trained " +
                                "runnerStronger=$runnerStronger stale=$stale enabled=$enabled")
                        val missingBaseline = samples == 2 && trained.size == 1 && trained[0] != 0 &&
                            !stale && enabled
                        assertEquals(if (missingBaseline) 1 else 0,
                            observations.count { it == "VARIABLE_PROBE_ATTEMPT" }, observations.toString())
                    }
                }
            }
        }
    }
}
