package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpNumericalBoundingTest {
    @Test
    fun `numerical objective propagation should round in source variable units`() {
        val problem = Problem(0, 3, arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 6)),
            arrayOf(Linear(intArrayOf(-1, -1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.GE, 3)))
        val session = PropagationSession(problem)
        val model = LpBuilder().apply {
            addVar(0L, 3L)
            addVar(0L, 3L)
            addVar(0L, 6L, cost = 1L)
            addRow(intArrayOf(0, 1, 2), longArrayOf(-1L, -1L, 1L), Relation.EQ, 0L)
            addRow(intArrayOf(0, 1), longArrayOf(2L, 1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0, 1, 2), booleanArrayOf(false, false, false), 0L,
            intArrayOf(0, 1, 2), intArrayOf())
        val point = RevisedSimplex(model).use { assertNotNull(it.solve()) }
        val sink = SolveStatsSink(backend = "numerical-bound")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(0L, 0L, 1L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(model, point.duals, Cancellation.Never))

            engine.applyNumericalBound(relaxation, lower, Double.POSITIVE_INFINITY, session,
                2, true, Cancellation.Never, true)

            assertEquals(2L, session.intDomain(2).min)
        }
    }

    @Test
    fun `an integral numerical lower bound should prune an equal incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(2, 10)), emptyArray())
        val session = PropagationSession(problem)
        val model = LpBuilder().apply { addVar(2L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L,
            intArrayOf(0), intArrayOf())
        val sink = SolveStatsSink(backend = "numerical-bound")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never))

            val pruned = engine.applyNumericalBound(relaxation, lower, 2.0, session,
                -1, true, Cancellation.Never, false)

            assertTrue(pruned)
            assertEquals(1L, sink.lp.snapshot().phases.getValue("NUMERICAL_CONSUMPTION_NODE")
                .outcomes.getValue("BOUND_PRUNE"))
        }
    }

    @Test
    fun `a foreign numerical bound should not propagate the current objective`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        val first = LpBuilder().apply { addVar(3L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val current = LpBuilder().apply { addVar(0L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(current, intArrayOf(0), booleanArrayOf(false), 0L,
            intArrayOf(0), intArrayOf())
        val sink = SolveStatsSink(backend = "numerical-bound")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(first, doubleArrayOf(), Cancellation.Never))

            val pruned = engine.applyNumericalBound(relaxation, lower, Double.POSITIVE_INFINITY, session,
                0, true, Cancellation.Never, true)

            assertFalse(pruned)
            assertEquals(IntDomain(0, 10), session.intDomain(0))
        }
    }

    @Test
    fun `numerical deductions should not renew a spent containing grant`() {
        var remaining = 2L
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val model = LpBuilder().apply { addVar(0L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val sink = SolveStatsSink(backend = "numerical-bound")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), LpParams(), sink,
            LpSolveContext.Numerical, deferred = true, nodeWorkAllowance = { remaining },
            containingWork = { remaining = (remaining - it).coerceAtLeast(0L) }).use { engine ->
            val before = engine.totalSolveWork()

            val first = engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never)
            val second = engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never)

            assertNull(first)
            assertNull(second)
            assertEquals(0L, remaining)
            assertEquals(2L, engine.totalSolveWork() - before)
        }
    }

    @Test
    fun `numerical reasons should retain isolated column bound premises`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 10), IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 3L)
        session.implyIntAtMost(1, 7L)
        val model = LpBuilder().apply {
            addVar(3L, 10L, cost = 1L)
            addVar(0L, 7L, cost = 2L)
        }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0, 1), booleanArrayOf(false, false), 0L,
            intArrayOf(0, 1), intArrayOf())
        val sink = SolveStatsSink(backend = "numerical-bound")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L, 2L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val reason = assertNotNull(engine.numericalPremiseClause(relaxation, session, Cancellation.Never))

            assertTrue(session.boundLeLit(0, 2L, positive = true) in reason)
            assertTrue(session.boundLeLit(1, 7L, positive = false) in reason)
        }
    }
}
