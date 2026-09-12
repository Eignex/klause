package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpHarvestEpochTest {
    @Test
    fun `shaving uses only the original source and leaves the live owner available`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
            arrayOf(
                Linear(intArrayOf(1, -1, -1, -1), intArrayOf(3, 0, 1, 2), LinearOp.GE, 0),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            ),
        )
        for (plan in listOf(
            LpPlan(bounding = true, variableShaving = true),
            LpPlan(bounding = true, objectiveShaving = true),
        )) {
            LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)),
                LpParams(lpPlan = plan), SolveStatsSink(backend = "epochs"),
            ).use { engine ->
                val session = PropagationSession(problem)
                session.implyIntAtLeast(3, 3)
                assertTrue(engine.rebuildEpoch(session, Cancellation.Never))
                val before = engine.propagator.state
                engine.cutPool.add(Cut(intArrayOf(0), longArrayOf(1), Relation.GE, 100, global = true))
                val bounds = harvestEpochBounds(engine, Cancellation.Never)
                assertSame(before, engine.propagator.state)
                val objective = assertNotNull(bounds.singleOrNull { it.varId == 3 })
                assertEquals(2L, objective.lo)
                assertEquals(3L, objective.hi)
                for (x in 0L..1L) for (y in 0L..1L) for (z in 0L..1L) for (total in 0L..3L) {
                    if (x + y < 1 || y + z < 1 || x + z < 1 || total < x + y + z) continue
                    val values = longArrayOf(x, y, z, total)
                    for (bound in bounds) assertTrue(values[bound.varId] in bound.lo..bound.hi)
                }
            }
        }
    }

    @Test
    fun `cancelled source harvest exports no facts`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        LpEngine(problem, LinearObjective(), LpParams(lpPlan = LpPlan(bounding = true, variableShaving = true)),
            SolveStatsSink(backend = "epochs"),
        ).use { engine -> assertTrue(harvestEpochBounds(engine, Cancellation { true }).isEmpty()) }
    }
}
