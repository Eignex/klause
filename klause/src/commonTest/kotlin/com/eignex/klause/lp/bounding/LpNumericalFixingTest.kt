package com.eignex.klause.lp.bounding

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LpNumericalFixingTest {
    @Test
    fun `fixing should not combine a bound with another solve vector`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        val model = LpBuilder().apply {
            val x = addVar(0L, 10L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 0L)
        }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L,
            intArrayOf(0), intArrayOf())
        val sink = SolveStatsSink(backend = "numerical-fixing")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(model, doubleArrayOf(0.0), Cancellation.Never))

            engine.applyNumericalFixing(relaxation, doubleArrayOf(1.0), lower, 2.0, session,
                -1, true, null, Cancellation.Never, false)

            assertEquals(IntDomain(0, 10), session.intDomain(0))
        }
    }

    @Test
    fun `numerical reduced costs should pin either Boolean endpoint`() {
        for (cost in listOf(-2L, 2L)) {
            val problem = Problem(1, 0, emptyArray(), emptyArray())
            val session = PropagationSession(problem)
            val model = LpBuilder().apply { addVar(0L, 1L, cost = cost) }.build(Sense.MINIMIZE)
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(true), 0L,
                intArrayOf(), intArrayOf(0))
            val objective = LinearObjective(boolWeights = longArrayOf(cost))
            val sink = SolveStatsSink(backend = "numerical-fixing")
            LpEngine(problem, objective, LpParams(), sink, LpSolveContext.Numerical).use { engine ->
                val lower = assertNotNull(engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never))

                engine.applyNumericalFixing(relaxation, doubleArrayOf(), lower,
                    if (cost > 0L) 1.0 else -1.0, session, -1, true, null, Cancellation.Never, false)

                assertEquals(cost < 0L, session.boolValue(0))
            }
        }
    }

    @Test
    fun `numerical reduced costs should tighten either minimizing endpoint`() {
        for (cost in listOf(-2L, 2L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
            val session = PropagationSession(problem)
            val model = LpBuilder().apply { addVar(0L, 10L, cost = cost) }.build(Sense.MINIMIZE)
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L,
                intArrayOf(0), intArrayOf())
            val objective = LinearObjective(intCoefficients = longArrayOf(cost))
            val sink = SolveStatsSink(backend = "numerical-fixing")
            LpEngine(problem, objective, LpParams(), sink, LpSolveContext.Numerical).use { engine ->
                val lower = assertNotNull(engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never))

                engine.applyNumericalFixing(relaxation, doubleArrayOf(), lower,
                    if (cost > 0L) 5.0 else -15.0, session, -1, true, null, Cancellation.Never, false)

                assertEquals(if (cost > 0L) IntDomain(0, 2) else IntDomain(8, 10), session.intDomain(0))
            }
        }
    }

    @Test
    fun `numerical fixing should retain affine objective units`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 40)), emptyArray())
        val session = PropagationSession(problem)
        val model = LpBuilder().apply { addVar(0L, 40L, cost = 2L) }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 10L,
            intArrayOf(0), intArrayOf())
        val objective = LinearObjective(intCoefficients = longArrayOf(2L), constant = 10L)
        val sink = SolveStatsSink(backend = "numerical-fixing")
        LpEngine(problem, objective, LpParams(), sink, LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never))

            engine.applyNumericalFixing(relaxation, doubleArrayOf(), lower, 50.0, session,
                0, true, null, Cancellation.Never, true)

            assertEquals(IntDomain(0, 19), session.intDomain(0))
        }
    }

    @Test
    fun `a foreign numerical bound should not fix the current objective`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        val first = LpBuilder().apply { addVar(0L, 10L, cost = 2L) }.build(Sense.MINIMIZE)
        val second = LpBuilder().apply { addVar(0L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(second, intArrayOf(0), booleanArrayOf(false), 0L,
            intArrayOf(0), intArrayOf())
        val sink = SolveStatsSink(backend = "numerical-fixing")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(first, doubleArrayOf(), Cancellation.Never))
            val before = engine.totalSolveWork()

            engine.applyNumericalFixing(relaxation, doubleArrayOf(), lower, 2.0, session,
                -1, true, null, Cancellation.Never, false)

            assertEquals(IntDomain(0, 10), session.intDomain(0))
            assertEquals(before, engine.totalSolveWork())
        }
    }

    @Test
    fun `cancelled numerical fixing should retain the current domain`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray())
        val session = PropagationSession(problem)
        val model = LpBuilder().apply { addVar(0L, 10L, cost = 1L) }.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false), 0L,
            intArrayOf(0), intArrayOf())
        val sink = SolveStatsSink(backend = "numerical-fixing")
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1L)), LpParams(), sink,
            LpSolveContext.Numerical).use { engine ->
            val lower = assertNotNull(engine.numericalObjectiveBound(model, doubleArrayOf(), Cancellation.Never))

            engine.applyNumericalFixing(relaxation, doubleArrayOf(), lower, 2.0, session,
                -1, true, null, Cancellation { true }, false)

            assertEquals(IntDomain(0, 10), session.intDomain(0))
        }
    }
}
