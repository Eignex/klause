package com.eignex.klause.lp.bounding

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpBoundingClosureTest : RuntimeInitializedTest() {
    @Test
    fun `cut infeasibility closes independently of the objective cutoff`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.EQ, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.EQ, 1),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val session = PropagationSession(problem)
        val sink = SolveStatsSink("cut-closure")
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = assertNotNull(engine.nodeRelaxation(relaxer, session))
            assertTrue(engine.cutPool.add(Cut(base.intColOf, longArrayOf(1L, 1L, 1L),
                Relation.GE, 2L, global = true), base))

            val result = engine.sparseSafePrune(relaxer, session, 100.0, sink, ExecutionControl.Never, -1, true)

            assertTrue(result.prune)
            assertEquals(Double.POSITIVE_INFINITY, engine.lastPruneBound)
        }
    }

    @Test
    fun `mixed row conflict closes independently of the objective cutoff`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.5),
            ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.25))
            val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
            val session = PropagationSession(problem)
            val sink = SolveStatsSink("mixed-row-closure")
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val result = engine.sparseSafePrune(assertNotNull(engine.lpRelaxer), session, 100.0,
                    sink, ExecutionControl.Never, -1, true, learn = learn)

                assertTrue(result.prune)
                assertEquals(Double.POSITIVE_INFINITY, engine.lastPruneBound)
            }
        }
    }

    @Test
    fun `strict source infeasibility closes independently of the objective cutoff`() {
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -1.0),
                LinearOp.GE, -2.0, strict = true),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.GE, 3.0),
        ), numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val session = PropagationSession(problem)
        val sink = SolveStatsSink("strict-closure")
        LpEngine(problem, LinearObjective(realCoefficients = doubleArrayOf(1.0, 1.0)),
            LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val result = engine.sparseSafePrune(assertNotNull(engine.lpRelaxer), session, 100.0,
                sink, ExecutionControl.Never, -1, true)

            assertTrue(result.prune)
            assertEquals(Double.POSITIVE_INFINITY, engine.lastPruneBound)
        }
    }

    @Test
    fun `strict source dominance retains the proved bound above the gap cutoff`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), arrayOf(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.5, strict = true),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val session = PropagationSession(problem)
        val sink = SolveStatsSink("strict-bound-closure")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            val result = engine.sparseSafePrune(assertNotNull(engine.lpRelaxer), session, 1.0,
                sink, ExecutionControl.Never, -1, true)

            assertTrue(result.prune)
            assertEquals(1.5, engine.lastPruneBound)
        }
    }

    @Test
    fun `objective propagation conflict closes independently of the objective cutoff`() {
        for (learn in listOf(false, true)) {
            val problem = Problem(0, 4,
                arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3)),
                arrayOf(
                    Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                    Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                    Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                    Linear(intArrayOf(2, 2, 2, -1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0),
                ))
            val session = PropagationSession(problem)
            val sink = SolveStatsSink("objective-conflict-closure")
            LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)),
                LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                val result = engine.sparseSafePrune(assertNotNull(engine.lpRelaxer), session, 100.0,
                    sink, ExecutionControl.Never, 3, true, learn = learn)

                assertTrue(result.prune)
                assertEquals(Double.POSITIVE_INFINITY, engine.lastPruneBound)
            }
        }
    }
}
