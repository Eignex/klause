package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.RootPrimalTargets
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.bounding.SPARSE_LP_MAX_PREPARATION_WORK
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpProjectionStop
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpRootComponentsTest {
    @Test
    fun `completed component values survive a restart without another numerical query`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val factory = RecordingLpEngineFactory()
        val offered = 1_000_000L
        val sink = SolveStatsSink("completed-component-restart")
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), sink,
            LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 1)
            session.pinInt(2, 1)
            val queries = factory.floatAllowances.size

            engine.pickRootComponent(session, Cancellation.Never)
            engine.restartRootPrimalTargets()
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), ordered)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `unfinished component pins supply no restart values`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val factory = RecordingLpEngineFactory()
        val offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("completed-component-values"),
            LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 1)
            val queries = factory.floatAllowances.size

            engine.pickRootComponent(session, Cancellation.Never)
            engine.restartRootPrimalTargets()
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(0L, 1L), ordered)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `discarding root targets releases completed component preferences`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val factory = RecordingLpEngineFactory()
        val offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("completed-component-values"),
            LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 1)
            session.pinInt(2, 1)
            engine.pickRootComponent(session, Cancellation.Never)
            val queries = factory.floatAllowances.size

            engine.clearRootPrimalTargets()
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(0L, 1L), ordered)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `replacing numerical guidance preserves completed component preferences`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val factory = RecordingLpEngineFactory()
        val offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("completed-component-values"),
            LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 1)
            session.pinInt(2, 1)
            engine.pickRootComponent(session, Cancellation.Never)
            val queries = factory.floatAllowances.size

            val relaxation = assertNotNull(engine.lpRelaxer).build(PropagationSession(problem))
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n))
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), ordered)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `spent component allowance withholds restart values`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val factory = RecordingLpEngineFactory()
        var offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("completed-component-values"),
            LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 1)
            session.pinInt(2, 1)
            offered = 0L
            val queries = factory.floatAllowances.size

            engine.pickRootComponent(session, Cancellation.Never)
            engine.restartRootPrimalTargets()
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(0L, 1L), ordered)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `cancelled completed component reads supply no restart values`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        val factory = RecordingLpEngineFactory()
        val offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("completed-component-values"),
            LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val session = PropagationSession(problem)
            engine.pickRootComponent(session, Cancellation.Never)
            session.pinInt(0, 1)
            session.pinInt(2, 1)
            val queries = factory.floatAllowances.size

            engine.pickRootComponent(session, Cancellation { engine.rootComponentValues != null })
            engine.restartRootPrimalTargets()
            val ordered = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(0L, 1L), ordered)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `known component targets precede unknown components`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, 2, 4, 5), LinearOp.LE, 4),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 2),
        ))
        val targets = RootPrimalTargets(IntArray(6) { it }, intArrayOf(),
            doubleArrayOf(0.0, 0.9, 0.0, 0.25, 0.0, 0.0),
            booleanArrayOf(false, true, false, true, false, false))

        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter(), targets = targets))

        assertEquals(listOf(3, 1, 0, 2, 4, 5), order.members.toList())
        assertEquals(listOf(0, 2, 6), order.offsets.toList())
    }

    @Test
    fun `nonfinite and unavailable component targets preserve source ordering`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, 2, 4, 5), LinearOp.LE, 4),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 2),
        ))
        for (coordinate in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.5)) {
            val available = !coordinate.isFinite()
            val targets = RootPrimalTargets(IntArray(6) { it }, intArrayOf(),
                doubleArrayOf(0.0, coordinate, 0.0, coordinate, 0.0, 0.0),
                booleanArrayOf(false, available, false, available, false, false))

            val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter(), targets = targets))

            assertEquals(listOf(0, 2, 4, 5, 1, 3), order.members.toList())
            assertEquals(listOf(0, 4, 6), order.offsets.toList())
        }
    }

    @Test
    fun `fractional coordinates precede integral coordinates within each component`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, 2, 4, 6), LinearOp.LE, 4),
            Linear(intArrayOf(1, 1, 1, 1), intArrayOf(1, 3, 5, 7), LinearOp.LE, 4),
        ))
        val columns = intArrayOf(6, 1, 4, 3, 2, 5, 0, 7)
        val coordinates = DoubleArray(8)
        for ((variable, value) in listOf(1.0, 0.9, -0.5, -0.5, 0.25, 0.5, 0.5, 0.0).withIndex()) {
            coordinates[columns[variable]] = value
        }
        val targets = RootPrimalTargets(columns, intArrayOf(), coordinates, null)

        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter(), targets = targets))

        assertEquals(listOf(2, 6, 4, 0, 3, 5, 1, 7), order.members.toList())
    }

    @Test
    fun `unknown and nonfinite coordinates follow known component targets in source order`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))
        for (unknown in listOf(0.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            val targets = RootPrimalTargets(IntArray(6) { it }, intArrayOf(),
                doubleArrayOf(1.0, Double.NaN, 0.25, unknown, 0.5, 0.0),
                booleanArrayOf(true, true, true, false, false, true))

            val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter(), targets = targets))

            assertEquals(listOf(2, 0, 4, 5, 1, 3), order.members.toList())
        }
    }

    @Test
    fun `target ordering cannot publish a component order beyond its preparation allowance`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))
        val graph = LpProjectionMeter()
        assertNotNull(rootComponentOrder(problem, graph))
        val meter = LpProjectionMeter(graph.work + 1L, rootComponentPreparationBytes(6))
        val targets = RootPrimalTargets(IntArray(6) { it }, intArrayOf(), DoubleArray(6) { 0.5 }, null)

        assertFailsWith<LpProjectionStop> { rootComponentOrder(problem, meter, targets = targets) }

        assertEquals(graph.work, meter.work)
        assertTrue(meter.allocation <= rootComponentPreparationBytes(6))
    }

    @Test
    fun `a fresh slice can fund preparation after an unstarted component attempt`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
        for (boundary in listOf("work", "clock", "initial-poll")) {
            var offered = 1_000_000L
            val factory = RecordingLpEngineFactory()
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), SolveStatsSink("pending-component-preparation"),
                LpSolveContext(factory), nodeWorkAllowance = { offered }).use { engine ->
                assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
                assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
                val session = PropagationSession(problem)
                val queries = factory.floatAllowances.size
                var polls = 0
                val token = when (boundary) {
                    "work" -> Cancellation.Never
                    "clock" -> Cancellation { true }
                    else -> Cancellation { ++polls >= 2 }
                }
                if (boundary == "work") offered = 0L
                assertNull(engine.pickRootComponent(session, token), boundary)
                offered = 1_000_000L

                val chosen = engine.pickRootComponent(session, Cancellation.Never)

                assertEquals(VarRef.IntVar(0), chosen, boundary)
                assertEquals(queries, factory.floatAllowances.size, boundary)
            }
        }
    }

    @Test
    fun `fixed endpoints do not join otherwise independent live components`() {
        val problem = Problem(0, 5, Array(5) { IntDomain(0, if (it == 4) 0 else 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 4), LinearOp.GE, 1),
        ))
        val session = PropagationSession(problem)

        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter(), session))

        assertEquals(listOf(0, 2, 1, 3, 4), order.members.toList())
    }

    @Test
    fun `paid lp work withholds a fresh component preparation grant`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = LongArray(6) { 1L })
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("charged-component-admission")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            val targets = assertNotNull(engine.rootPrimalTargets)
            engine.noteNodeOverhead(SPARSE_LP_MAX_PREPARATION_WORK)
            assertTrue(engine.retainedRootRemainingWork() > 0L)

            val chosen = engine.pickRootComponent(PropagationSession(problem), Cancellation.Never)

            assertNull(chosen)
            assertNull(targets.components)
        }
    }

    @Test
    fun `declared integer components stay contiguous in source coordinates`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))

        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter()))

        assertEquals(listOf(0, 2, 4, 1, 3, 5), order.members.toList())
        assertEquals(listOf(0, 3, 6), order.offsets.take(order.groups + 1))
        assertTrue(order.retainedBytes <= rootComponentPreparationBytes(6))
    }

    @Test
    fun `a factor joining components preserves the connected fallback`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
        ))

        val order = rootComponentOrder(problem, LpProjectionMeter())

        assertNull(order)
    }

    @Test
    fun `preparation refuses work and storage before allocating component arrays`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, emptyList())
        for ((work, bytes) in listOf(0L to Long.MAX_VALUE, Long.MAX_VALUE to 0L)) {
            val meter = LpProjectionMeter(work, bytes)

            assertFailsWith<LpProjectionStop> { rootComponentOrder(problem, meter) }

            assertEquals(0L, meter.work)
            assertEquals(0L, meter.allocation)
        }
    }

    @Test
    fun `interrupted component preparation preserves its completed charge`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))
        var polls = 0
        val meter = LpProjectionMeter(1_000L, rootComponentPreparationBytes(6), Cancellation { ++polls >= 2 })

        assertFailsWith<LpProjectionStop> { rootComponentOrder(problem, meter) }

        assertTrue(meter.work > 0L && meter.work <= 1_000L)
        assertTrue(meter.allocation <= rootComponentPreparationBytes(6))
    }

    @Test
    fun `component choice revisits retracted variables and charges membership visits`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 4), LinearOp.LE, 3),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 3, 5), LinearOp.LE, 3),
        ))
        val order = assertNotNull(rootComponentOrder(problem, LpProjectionMeter()))
        val session = PropagationSession(problem)
        assertEquals(0, firstRootComponentVariable(order, session, Cancellation.Never))
        session.pinInt(0, 0)
        assertEquals(2, firstRootComponentVariable(order, session, Cancellation.Never))
        session.pinInt(2, 0)
        assertEquals(4, firstRootComponentVariable(order, session, Cancellation.Never))
        session.popToLevel(0)
        val before = session.work

        val variable = firstRootComponentVariable(order, session, Cancellation.Never)

        assertEquals(0, variable)
        assertEquals(before + 1L, session.work)
    }
}
