package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpRootTargetsTest {

    @Test
    fun `completed component preferences take precedence over auxiliary root coordinates`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 3, 4))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("component-and-auxiliary-value-order")
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), sink, LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 0)
            engine.pickRootComponent(session, Cancellation.Never)
            engine.restartRootPrimalTargets()
            engine.cpAdapter.reset()
            engine.rootRoundingWarmStart = null
            assertNull(engine.lpRoundingProbe(objective, Cancellation.Never, accept = { null }))
            val constructed = factory.calls.size

            val completed = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(1L, 0L)).toList()
            assertNull(sink.snapshot().lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]
                ?.outcomes?.get("AUXILIARY_ROOT_VALUE_ORDER_OFFERED"))
            val auxiliary = engine.orderRootValues(VarRef.IntVar(1), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(0L, 1L), completed)
            assertEquals(listOf(1L, 0L), auxiliary)
            assertEquals(constructed, factory.calls.size)
            assertNull(engine.propagator.state)
            assertEquals(1L, sink.snapshot().lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]
                ?.outcomes?.get("AUXILIARY_ROOT_VALUE_ORDER_OFFERED"))
        }
    }


    @Test
    fun `completed auxiliary root points guide ordinary values without a live numerical owner`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("auxiliary-root-value-order")
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), sink, LpSolveContext(factory)).use { engine ->
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val constructed = factory.calls.size

            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), ordered)
            assertEquals(constructed, factory.calls.size)
            assertNull(engine.propagator.state)
            assertEquals(1L, sink.snapshot().lp.phases["PRIMAL_VALUE_GUIDANCE_ROOT"]
                ?.outcomes?.get("AUXILIARY_ROOT_VALUE_ORDER_OFFERED"))
        }
    }

    @Test
    fun `cancelled auxiliary value transfers resume under a fresh token without another query`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = RecordingLpEngineFactory()
        var stopped = false
        val token = Cancellation { stopped }
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("resumed-auxiliary-value-order"),
            LpSolveContext(factory)).use { engine ->
            assertNull(engine.lpRoundingProbe(objective, token, accept = {
                stopped = true
                null
            }))
            val constructed = factory.calls.size
            assertFalse(engine.retainRootPrimalTargets(token))
            stopped = false

            assertTrue(engine.retainRootPrimalTargets(token))
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), ordered)
            assertEquals(constructed, factory.calls.size)
        }
    }

    @Test
    fun `auxiliary value transfer requires its source objective and retained root plan`() {
        for (guard in listOf("source", "unstamped", "objective", "masked", "ordinary", "branching", "unfinished")) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
            val source = when (guard) {
                "source" -> Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyList())
                "unstamped" -> null
                else -> problem
            }
            val guidanceObjective = if (guard == "objective") {
                LinearObjective(intCoefficients = longArrayOf(1, 2))
            } else objective
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, branching = guard != "branching",
                    componentOwnership = guard != "ordinary", rootCutHarvest = false)),
                SolveStatsSink("qualified-auxiliary-value-order")).use { engine ->
                engine.rootRoundingWarmStart = RootRoundingWarmStart(guidanceObjective, relaxation,
                    primal = if (guard == "unfinished") null else DoubleArray(relaxation.model.n) { 1.0 },
                    knownColumns = if (guard == "masked") BooleanArray(relaxation.model.n) { true } else null,
                    sourceProblem = source, auxiliaryPoint = true)

                val retained = engine.retainRootPrimalTargets(Cancellation.Never)
                val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

                assertFalse(retained, guard)
                assertEquals(listOf(0L, 1L), ordered, guard)
            }
        }
    }

    @Test
    fun `finished masked rounding guides ordinary values without reading unknown payloads or solving again`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("masked-root-value-order"),
            LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val known = BooleanArray(relaxation.model.n)
            known[relaxation.intColOf[0]] = true
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n) { 1.0 }, knownColumns = known)
            val queries = factory.floatAllowances.size
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            assertNull(engine.rootRoundingWarmStart)
            val before = engine.totalSolveWork()

            val guided = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()
            val ordinary = engine.orderRootValues(VarRef.IntVar(1), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), guided)
            assertEquals(listOf(0L, 1L), ordinary)
            assertEquals(queries, factory.floatAllowances.size)
            assertEquals(before + 2L, engine.totalSolveWork())
            assertTrue(engine.orderRootValues(VarRef.IntVar(0), emptySequence()).none())
        }
    }

    @Test
    fun `fresh node values take precedence over retained root targets`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("fresh-node-before-root-target")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val hints = LpHints(2, 0)
            val nodePoint = DoubleArray(relaxation.model.n)
            nodePoint[relaxation.intColOf[1]] = 1.0
            hints.record(relaxation, nodePoint, DoubleArray(relaxation.model.m))
            val before = engine.totalSolveWork()

            val ordered = hints.order(VarRef.IntVar(0),
                engine.orderRootValues(VarRef.IntVar(0), sequenceOf(1L, 0L)))

            assertEquals(0L, ordered.first())
            assertEquals(before, engine.totalSolveWork())
        }
    }

    @Test
    fun `replacing a root guide expires earlier ordinary value targets`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("replaced-root-value-target")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            assertEquals(1L, engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).first())
            val earlier = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L))

            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n))

            assertEquals(0L, earlier.first())
            assertEquals(0L, engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).first())
        }
    }

    @Test
    fun `disabling lp branching preserves ordinary value order after root rounding`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, branching = false,
                componentOwnership = true, rootCutHarvest = false)),
            SolveStatsSink("disabled-root-value-target")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L))

            assertEquals(listOf(0L, 1L), ordered.toList())
        }
    }
}
