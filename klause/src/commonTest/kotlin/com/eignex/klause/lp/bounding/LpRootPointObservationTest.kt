package com.eignex.klause.lp.bounding

import com.eignex.klause.backtrack.lp.lpRoundingProbe
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.ExecutionControl
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRootPointObservationTest {
    @Test
    fun `declined root retention emits no retained owner assertion`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val events = mutableListOf<LpRootPointStage>()
        LpEngine(problem, objective, LpParams(), SolveStatsSink("declined-owner-observation"),
            rootPointObserver = LpRootPointObserver { events.add(it.stage) }).use { engine ->
            engine.retainRootPrimalOwner(relaxation)

            assertNull(engine.rootRoundingWarmStart)
            assertTrue(LpRootPointStage.OWNER !in events)
        }
    }

    @Test
    fun `non retained primal calls journal their existing tableau query`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3) { 2L })
        val events = mutableListOf<LpRootPointObservation>()
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("non-retained-query-observation"),
            rootPointObserver = LpRootPointObserver { events.add(it) }).use { engine ->
            engine.lpRoundingProbe(objective, ExecutionControl.Never)

            val calls = events.filter { it.stage == LpRootPointStage.QUERY_CALL }
            assertEquals(LpRootPointDisposition.ENTERED, calls.first().disposition)
            assertEquals(LpRootPointDisposition.OPTIMAL, calls.last().disposition)
            assertEquals(calls.first().query, calls.last().query)
            assertSame(calls.first().model, calls.last().model)
            assertNull(calls.last().termination)
        }
    }

    @Test
    fun `target refusal preserves first guard priority without polling cancellation`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        for ((guard, expected) in listOf("branch" to LpRootPointDisposition.BRANCH_DISABLED,
            "objective" to LpRootPointDisposition.OBJECTIVE, "shape" to LpRootPointDisposition.SHAPE,
            "owner" to LpRootPointDisposition.OWNER)) {
            val events = mutableListOf<LpRootPointDisposition>()
            val requested = if (guard == "objective") LinearObjective(intCoefficients = LongArray(3)) else objective
            val coordinates = DoubleArray(if (guard == "shape") 0 else relaxation.model.n)
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(branching = guard != "branch")),
                SolveStatsSink("target-guard-observation"), rootPointObserver = LpRootPointObserver {
                    if (it.stage == LpRootPointStage.TARGETS) events.add(it.disposition)
                }).use { engine ->
                engine.rootRoundingWarmStart = RootRoundingWarmStart(requested, relaxation, primal = coordinates)

                val retained = engine.retainRootPrimalTargets(ExecutionControl { error("unexpected poll") })

                assertEquals(false, retained)
                assertEquals(expected, events.last())
            }
        }
    }

    @Test
    fun `copy observation preserves cancellation polls and retained points`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0,
            DoubleArray(relaxation.model.m), DoubleArray(relaxation.model.n) { 0.5 })
        for (stopOn in 1..3) {
            val work = mutableListOf<Pair<Int, Long>>()
            for (observed in listOf(false, true)) {
                val events = mutableListOf<LpRootPointObservation>()
                var polls = 0
                var allowanceReads = 0
                val observer = if (observed) LpRootPointObserver { events.add(it) } else null
                LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true,
                    componentOwnership = true)), SolveStatsSink("copy-observation"),
                    nodeWorkAllowance = { allowanceReads++; 1_000_000L }, rootPointObserver = observer).use { engine ->
                    allowanceReads = 0
                    engine.retainRootPrimalGuidance(relaxation, result, ExecutionControl { ++polls >= stopOn })
                    work.add(allowanceReads to engine.totalSolveWork())

                    assertEquals(minOf(stopOn, 2), polls)
                    assertEquals(stopOn == 3, engine.rootRoundingWarmStart?.primal != null)
                    if (observed) assertEquals(if (stopOn == 3) LpRootPointDisposition.RETAINED
                        else LpRootPointDisposition.CANCELLED,
                        events.last { it.stage == LpRootPointStage.COPY }.disposition)
                }
            }
            assertEquals(work[0], work[1])
        }
    }

    @Test
    fun `probe refusal precedes cancellation observation`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val events = mutableListOf<LpRootPointObservation>()
        LpEngine(problem, objective, LpParams(), SolveStatsSink("probe-observation"),
            rootPointObserver = LpRootPointObserver { events.add(it) }).use { engine ->
            engine.retainRootPrimalGuidance(relaxation, null, ExecutionControl { error("unexpected poll") })

            assertEquals(LpRootPointDisposition.PROBE_DISABLED, events.single().disposition)
        }
    }

    @Test
    fun `observer exceptions preserve point retention and retirement`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val result = FloatLpResult(Basis(intArrayOf(), emptyArray()), 0.0,
            DoubleArray(relaxation.model.m), DoubleArray(relaxation.model.n) { 0.5 })
        val sink = SolveStatsSink("failed-observation")
        val engine = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            sink, rootPointObserver = LpRootPointObserver { error("observer failure") })

        engine.retainRootPrimalGuidance(relaxation, result, ExecutionControl.Never)
        assertNotNull(engine.rootRoundingWarmStart?.primal)
        engine.close()

        assertNull(engine.rootRoundingWarmStart)
        assertEquals(1L, sink.lp.snapshot().phases.getValue("ROOT_POINT_OBSERVATION_ROOT").outcomes["FAILED"])
    }

    @Test
    fun `preclose interruption still retires retained guidance`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        for (failure in listOf(AssertionError("close observation"), CancellationException("close observation"))) {
            val events = mutableListOf<LpRootPointStage>()
            val engine = LpEngine(problem, objective, LpParams(), SolveStatsSink("interrupted-close-observation"),
                rootPointObserver = LpRootPointObserver {
                    events.add(it.stage)
                    if (it.stage == LpRootPointStage.CLOSE_BEGIN) throw failure
                })
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = DoubleArray(relaxation.model.n))

            val thrown = assertFailsWith<Throwable> { engine.close() }

            assertSame(failure, thrown)
            assertNull(engine.rootRoundingWarmStart)
            assertTrue(LpRootPointStage.CLOSE_END !in events)
            engine.close()
        }
    }

    @Test
    fun `objective engines retain actual source and objective identities`() {
        val problem = boundedLpEngineProblem()
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val other = LinearObjective(intCoefficients = LongArray(3) { 1L })
        val events = mutableListOf<LpRootPointObservation>()
        LpEngine(problem, objective, LpParams(), SolveStatsSink("objective-owner-observation"),
            rootPointObserver = LpRootPointObserver { events.add(it) }).use { engine ->
            engine.observeRootPoint(LpRootPointStage.COPY, LpRootPointDisposition.NO_POINT)
            engine.forObjective(objective, ExecutionControl.Never).use {
                it.observeRootPoint(LpRootPointStage.COPY, LpRootPointDisposition.NO_POINT)
            }
            engine.forObjective(other, ExecutionControl.Never).use {
                it.observeRootPoint(LpRootPointStage.COPY, LpRootPointDisposition.NO_POINT)
            }
        }

        val owners = events.filter { it.stage == LpRootPointStage.COPY }
        assertEquals(3, owners.size)
        assertNotSame(owners[0].owner, owners[1].owner)
        assertNotSame(owners[0].owner, owners[2].owner)
        owners.forEach { assertSame(problem, it.source) }
        assertSame(objective, owners[0].objective)
        assertSame(objective, owners[1].objective)
        assertSame(other, owners[2].objective)
    }

    @Test
    fun `automatic point events connect query output to private target coordinates`() {
        val problem = boundedLpEngineProblem().withSettings(
            ProblemSettings(lpMaxTableauCells = 1L, lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = LongArray(3) { 2L })
        val events = mutableListOf<LpRootPointObservation>()
        LpEngine(problem, objective, LpParams(lpConfig = LpConfig.AUTO), SolveStatsSink("automatic-point-events"),
            nodeWorkAllowance = { 1_000_000L }, rootPointObserver = LpRootPointObserver { events.add(it) })
            .use { engine ->
                assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))

                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
                assertTrue(engine.retainRootPrimalTargets(ExecutionControl.Never))

                val returned = events.last { it.stage == LpRootPointStage.QUERY_CALL &&
                    it.disposition == LpRootPointDisposition.OPTIMAL }
                val copied = events.last { it.stage == LpRootPointStage.COPY &&
                    it.disposition == LpRootPointDisposition.RETAINED }
                val targets = events.last { it.stage == LpRootPointStage.TARGETS &&
                    it.disposition == LpRootPointDisposition.RETAINED }
                assertSame(returned.owner, copied.owner)
                assertSame(returned.point, copied.previousPoint)
                assertNotSame(returned.point, copied.point)
                assertSame(copied.point, targets.point)
                assertNull(copied.sourceRoot)
            }
    }
}
