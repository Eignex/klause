package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.LpRounding
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpComponentRoundingTest {
    @Test
    fun `masked component admission uses only known fractional targets`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it == 4) 2 else 1) },
            (0 until 2).flatMap { group ->
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (group == 0) 2 else -2
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                        (if (group == 0) 2 else -2) - coefficients.drop(1).count { it < 0 })
                }
            }).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0, -1, 0, 0, 0))
        for (kind in listOf("unknown", "integral", "fractional")) {
            val factory = RecordingLpEngineFactory()
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), SolveStatsSink("masked-component-admission"),
                LpSolveContext(factory)).use { engine ->
                assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
                val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
                val known = BooleanArray(relaxation.model.n)
                val point = DoubleArray(relaxation.model.n) { 1.75 }
                if (kind != "unknown") {
                    for (variable in 0 until 4) {
                        val column = relaxation.intColOf[variable]
                        known[column] = true
                        point[column] = if (kind == "integral") 1.0 else if (variable == 0) 0.25 else 0.5
                    }
                }
                val guide = RootRoundingWarmStart(objective, relaxation, primal = point, knownColumns = known)
                engine.rootRoundingWarmStart = guide
                assertTrue(engine.retainRootPrimalTargets(ExecutionControl.Never))
                val constructed = factory.calls.size

                val prepared = engine.prepareRootComponentTrial(objective, ExecutionControl.Never)

                assertEquals(kind == "fractional", prepared, kind)
                if (prepared) {
                    val prefix = assertNotNull(guide.pinning)
                    val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))
                    assertEquals(listOf(1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L), sample.ints.toList())
                    assertEquals(prefix.work, engine.primalPropagationWork)
                } else {
                    assertNull(guide.pinning, kind)
                    assertEquals(0L, engine.primalPropagationWork, kind)
                }
                assertEquals(constructed, factory.calls.size, kind)
            }
        }
    }

    @Test
    fun `component trial admission separates paid numerical work from its preparation limit`() {
        for (rootLimit in listOf(8_000_000L, 150_000_000L)) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
            )).bake()
            val objective = LinearObjective(intCoefficients = LongArray(4) { 1L })
            var queries = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: ExecutionControl, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    var charged = false
                    return object : PersistentLpSolver by delegate {
                        override val lastMetrics: LpSolveMetrics get() = delegate.lastMetrics.copy(
                            workOps = delegate.lastMetrics.workOps + if (charged) 8_000_000L else 0L)

                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                            delegate.resolveBounds(allowance).also {
                                charged = true
                                queries++
                            }
                    }
                }
            }
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false, rootMaxWork = rootLimit)),
                SolveStatsSink("separate-preparation-limit"),
                LpSolveContext(factory)).use { engine ->
                assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
                assertTrue(engine.totalSolveWork() >= 8_000_000L)
                val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
                val guide = RootRoundingWarmStart(objective, relaxation,
                    primal = DoubleArray(relaxation.model.n) { 0.5 },
                    sourceProblem = problem, auxiliaryPoint = true)
                engine.rootRoundingWarmStart = guide
                val before = engine.totalSolveWork()
                val previousQueries = queries
                val funded = rootLimit > before

                val retained = engine.retainRootPrimalTargets(ExecutionControl.Never)
                val prepared = engine.prepareRootComponentTrial(objective, ExecutionControl.Never)

                assertEquals(funded, retained)
                assertEquals(funded, prepared)
                if (funded) {
                    val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))
                    assertTrue(sample.ints.all { it in 0L..1L })
                    assertTrue(sample.ints[0] + sample.ints[2] >= 1L && sample.ints[1] + sample.ints[3] >= 1L)
                    assertTrue(engine.totalSolveWork() > before)
                } else {
                    assertNull(guide.pinning)
                    assertEquals(before, engine.totalSolveWork())
                }
                assertEquals(previousQueries, queries)
            }
        }
    }

    @Test
    fun `handoff component trials wait for funding without constructing private pinning state`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        ))
        val objective = LinearObjective(intCoefficients = LongArray(4))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val guide = RootRoundingWarmStart(objective, relaxation,
            primal = DoubleArray(relaxation.model.n) { 0.5 }, sourceProblem = problem, auxiliaryPoint = true)
        val factory = RecordingLpEngineFactory()
        var offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("funded-handoff-trial"), LpSolveContext(factory),
            nodeWorkAllowance = { offered }).use { engine ->
            engine.rootRoundingWarmStart = guide
            assertTrue(engine.retainRootPrimalTargets(ExecutionControl.Never))
            offered = 0L
            assertFalse(engine.prepareRootComponentTrial(objective, ExecutionControl.Never))
            assertNull(guide.pinning)
            assertEquals(0L, engine.primalPropagationWork)
            offered = 1_000_000L
            assertTrue(engine.prepareRootComponentTrial(objective, ExecutionControl.Never))
            val prefix = assertNotNull(guide.pinning)

            val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

            assertTrue(sample.ints.all { it in 0L..1L })
            assertTrue(sample.ints[0] + sample.ints[2] >= 1L && sample.ints[1] + sample.ints[3] >= 1L)
            assertEquals(prefix.work, engine.primalPropagationWork)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `component rounding waits for funded progress without another numerical owner`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.GE, 1),
        ))
        val objective = LinearObjective(intCoefficients = LongArray(4))
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        val guide = RootRoundingWarmStart(objective, relaxation,
            primal = DoubleArray(relaxation.model.n) { 1.0 }, sourceProblem = problem, auxiliaryPoint = true)
        val factory = RecordingLpEngineFactory()
        var offered = 1_000_000L
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("funded-component-rounding"), LpSolveContext(factory),
            nodeWorkAllowance = { offered }).use { engine ->
            engine.rootRoundingWarmStart = guide
            assertTrue(engine.retainRootPrimalTargets(ExecutionControl.Never))
            val order = assertNotNull(engine.prepareRootComponentOrder(session, ExecutionControl.Never))
            val targets = assertNotNull(engine.rootPrimalTargets)
            offered = 0L
            assertNull(engine.prepareRootComponentRounding(targets, order, ExecutionControl.Never))
            offered = 1_000_000L

            assertNotNull(engine.prepareRootComponentRounding(targets, order, ExecutionControl.Never))
            val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

            assertEquals(List(4) { 1L }, sample.ints.toList())
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `independent components combine different rounding directions without another lp query`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it == 4) 2 else 1) },
            (0 until 2).flatMap { group ->
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (group == 0) 1 else -1
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                        (if (group == 0) 1 else -1) - coefficients.drop(1).count { it < 0 })
                }
            })
        val objective = LinearObjective(intCoefficients = LongArray(8))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n)
        for (v in 0 until 8) {
            primal[relaxation.intColOf[v]] =
                when (v) {
                    0 -> 0.25
                    4 -> 1.75
                    in 1..3 -> 0.4
                    else -> 0.6
                }
        }
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("independent-rounding"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation, primal = primal,
                sourceProblem = problem, auxiliaryPoint = true)

            val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L), sample.ints.toList())
            val verifier = finiteWitnessVerifier(problem.bake(), objective)
            assertIs<Verification.Accepted<*, *>>(
                verifier.verify(Candidate(sample, objective.evaluate(sample))),
            )
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `component repair skips unrelated pins across interrupted conflict retreats`() {
        for (interrupted in listOf(false, true)) {
            val width = 20
            val problem = Problem(0, 2 * width,
                Array(2 * width) { IntDomain(0, if (it % width == 0) 2 else 1) },
                (0 until 2).flatMap { group ->
                    val gate = group * width
                    listOf(1 to 2, 2 to 3, 3 to 1).flatMap { (a, b) ->
                        val variables = intArrayOf(gate, gate + a, gate + b)
                        listOf(Linear(intArrayOf(1, 1, 1), variables, LinearOp.GE, 2),
                            Linear(intArrayOf(1, -1, -1), variables, LinearOp.GE, 0))
                    } + (4 until width).map { offset ->
                        Linear(intArrayOf(1, 1), intArrayOf(gate, gate + offset), LinearOp.LE, 2)
                    }
                })
            val objective = LinearObjective(intCoefficients = LongArray(2 * width))
            val factory = RecordingLpEngineFactory()
            val sink = SolveStatsSink("conflict-component-retreat")
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), sink, LpSolveContext(factory)).use { engine ->
                assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
                val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
                val primal = DoubleArray(relaxation.model.n)
                val known = BooleanArray(primal.size)
                for (v in 0 until 2 * width) {
                    val column = relaxation.intColOf[v]
                    known[column] = v % width == 0 || v % width == 4
                    primal[column] = when (v % width) {
                        0 -> 1.0
                        4 -> 0.25
                        else -> 123.75
                    }
                }
                val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal,
                    knownColumns = known, sourceProblem = problem)
                val before = engine.primalPropagationWork
                val limit = ExecutionControl { engine.primalPropagationWork - before >= 200_000L }
                var expired = false
                var retreatDepth = -1
                var pinning: PropagationSession? = null
                val slice = ExecutionControl {
                    guidance.pinning?.let { pinning = it }
                    val pending = guidance.componentRounding
                    val repair = pending?.repair
                    if (interrupted && pending?.group == 1 && repair != null && repair.retreatLimit >= 0) {
                        if (retreatDepth < 0) retreatDepth = repair.depth
                        else if (repair.depth < retreatDepth) expired = true
                    }
                    expired || limit()
                }
                val constructed = factory.calls.size
                val queries = factory.floatAllowances.size
                engine.rootRoundingWarmStart = guidance

                val first = engine.lpRoundingProbe(objective, slice)
                val sample = if (interrupted) {
                    assertNull(first)
                    assertTrue(expired)
                    assertEquals(IntDomain(2, 2), assertNotNull(guidance.pinning).intDomain(0))
                    assertNotNull(engine.lpRoundingProbe(objective, limit))
                } else assertNotNull(first)

                assertEquals(2L, sample.ints[0])
                assertEquals(2L, sample.ints[width])
                assertIs<Verification.Accepted<*, *>>(
                    finiteWitnessVerifier(problem.bake(), objective)
                        .verify(Candidate(sample, objective.evaluate(sample))),
                )
                assertTrue(engine.primalPropagationWork - before < 200_000L)
                assertEquals(assertNotNull(pinning).work, engine.primalPropagationWork)
                assertTrue((sink.snapshot().lp.phases["PRIMAL_COMPONENT_REPAIR_RETREAT_ROOT"]
                    ?.outcomes?.get("CONFLICT") ?: 0L) >= 2L)
                assertEquals(constructed, factory.calls.size)
                assertEquals(queries, factory.floatAllowances.size)
            }
        }
    }

    @Test
    fun `component repair declines when its containing root work is spent`() {
        val problem = Problem(0, 6, Array(6) { IntDomain(0, 1) },
            (0 until 2).flatMap { group ->
                listOf(0 to 1, 1 to 2, 2 to 0).map { (a, b) ->
                    Linear(intArrayOf(1, 1), intArrayOf(3 * group + a, 3 * group + b), LinearOp.EQ, 1)
                }
            })
        val objective = LinearObjective(intCoefficients = LongArray(6))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val guidance = RootRoundingWarmStart(objective, relaxation,
            primal = DoubleArray(relaxation.model.n) { 0.5 }, sourceProblem = problem, auxiliaryPoint = true)
        val sink = SolveStatsSink("spent-component-repair")
        val factory = RecordingLpEngineFactory()
        var spend: (() -> Unit)? = null
        val token = ExecutionControl {
            if (guidance.componentRounding?.repair?.active == true) {
                val consume = spend
                spend = null
                consume?.invoke()
            }
            false
        }
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootMaxWork = 1_000_000L)), sink, LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            spend = { engine.noteSolveOps(1_000_000L) }

            val sample = engine.lpRoundingProbe(objective, token)

            assertNull(sample)
            assertNull(spend)
            assertEquals(1L, sink.snapshot().lp.phases["PRIMAL_COMPONENT_REPAIR_ROOT"]
                ?.outcomes?.get("WORK_DECLINED"))
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `interrupted component repair retains earlier component pins under a fresh token`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it % 4 == 0) 2 else 1) },
            (0 until 2).flatMap { group ->
                listOf(1 to 2, 2 to 3, 3 to 1).flatMap { (a, b) ->
                    val variables = intArrayOf(4 * group, 4 * group + a, 4 * group + b)
                    listOf(Linear(intArrayOf(1, 1, 1), variables, LinearOp.GE, 2),
                        Linear(intArrayOf(1, -1, -1), variables, LinearOp.GE, 0))
                }
            })
        val objective = LinearObjective(intCoefficients = LongArray(8))
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)),
            SolveStatsSink("resumed-component-repair"), LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val primal = DoubleArray(relaxation.model.n)
            val known = BooleanArray(primal.size)
            for (v in 0 until 8) {
                val column = relaxation.intColOf[v]
                known[column] = v % 4 < 2
                primal[column] = when (v % 4) {
                    0 -> 1.0
                    1 -> 0.5
                    else -> 123.75
                }
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal, knownColumns = known,
                sourceProblem = problem)
            var expired = false
            val slice = ExecutionControl {
                val pending = guidance.componentRounding
                val pinning = guidance.pinning
                if (pending?.group == 1 && pending.repair?.active == true &&
                    pinning?.intDomain(4)?.isFixed == true
                ) expired = true
                expired
            }
            val constructed = factory.calls.size
            val queries = factory.floatAllowances.size
            engine.rootRoundingWarmStart = guidance
            assertNull(engine.lpRoundingProbe(objective, slice))
            val prefix = assertNotNull(guidance.pinning)
            assertTrue(expired)
            assertEquals(IntDomain(2, 2), prefix.intDomain(0))
            val paid = engine.primalPropagationWork

            val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

            assertEquals(2L, sample.ints[0])
            assertEquals(2L, sample.ints[4])
            assertIs<Verification.Accepted<*, *>>(
                finiteWitnessVerifier(problem.bake(), objective)
                    .verify(Candidate(sample, objective.evaluate(sample))),
            )
            assertEquals(prefix.work, engine.primalPropagationWork)
            assertTrue(engine.primalPropagationWork > paid)
            assertEquals(constructed, factory.calls.size)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `partial owner refresh preserves a paused component repair and its earlier pins`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it % 4 == 0) 2 else 1) },
            (0 until 2).flatMap { group ->
                listOf(1 to 2, 2 to 3, 3 to 1).flatMap { (a, b) ->
                    val variables = intArrayOf(4 * group, 4 * group + a, 4 * group + b)
                    listOf(Linear(intArrayOf(1, 1, 1), variables, LinearOp.GE, 2),
                        Linear(intArrayOf(1, -1, -1), variables, LinearOp.GE, 0))
                }
            })
        val objective = LinearObjective(intCoefficients = LongArray(8))
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)),
            SolveStatsSink("refreshed-component-repair"), LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(ExecutionControl.Never, ExecutionControl.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val primal = DoubleArray(relaxation.model.n)
            val known = BooleanArray(primal.size)
            for (v in 0 until 8) {
                val column = relaxation.intColOf[v]
                known[column] = v % 4 < 2
                primal[column] = when (v % 4) {
                    0 -> 1.0
                    1 -> 0.5
                    else -> 123.75
                }
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal, knownColumns = known,
                sourceProblem = problem)
            var expired = false
            val slice = ExecutionControl {
                val pending = guidance.componentRounding
                val pinning = guidance.pinning
                if (pending?.group == 1 && pending.repair?.active == true &&
                    pinning?.intDomain(4)?.isFixed == true
                ) expired = true
                expired
            }
            val constructed = factory.calls.size
            val queries = factory.floatAllowances.size
            engine.rootRoundingWarmStart = guidance
            assertNull(engine.lpRoundingProbe(objective, slice))
            val prefix = assertNotNull(guidance.pinning)
            assertTrue(expired)
            assertEquals(IntDomain(2, 2), prefix.intDomain(0))
            val paid = engine.primalPropagationWork

            engine.retainRootPartialPrimalGuidance(relaxation, ExecutionControl.Never)
            assertEquals(IntDomain(2, 2), assertNotNull(engine.rootRoundingWarmStart?.pinning).intDomain(0))
            val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

            assertEquals(2L, sample.ints[0])
            assertEquals(2L, sample.ints[4])
            assertIs<Verification.Accepted<*, *>>(
                finiteWitnessVerifier(problem.bake(), objective)
                    .verify(Candidate(sample, objective.evaluate(sample))),
            )
            assertEquals(prefix.work, engine.primalPropagationWork)
            assertTrue(engine.primalPropagationWork > paid)
            assertEquals(constructed, factory.calls.size)
            assertEquals(queries, factory.floatAllowances.size)
        }
    }

    @Test
    fun `interrupted component rounding retains completed earlier pins under a fresh token`() {
        for (withinDirection in listOf(false, true)) {
            val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it == 4) 2 else 1) },
                (0 until 2).flatMap { group ->
                    (0 until 8).map { mask ->
                        val coefficients = IntArray(4) { v ->
                            if (v == 0) {
                                if (group == 0) 1 else -1
                            } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                        }
                        Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                            (if (group == 0) 1 else -1) - coefficients.drop(1).count { it < 0 })
                    }
                })
            val objective = LinearObjective(intCoefficients = LongArray(8))
            val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
            val primal = DoubleArray(relaxation.model.n)
            for (v in 0 until 8) {
                primal[relaxation.intColOf[v]] =
                    when (v) {
                        0 -> 0.25
                        4 -> 1.75
                        in 1..3 -> 0.4
                        else -> 0.6
                    }
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal,
                sourceProblem = problem, auxiliaryPoint = true)
            val factory = RecordingLpEngineFactory()
            var expired = false
            val slice = ExecutionControl {
                val pending = guidance.componentRounding
                val level = guidance.pinning?.decisionLevel ?: 0
                if (pending?.group == 1 && level >= (if (withinDirection) 6 else 4) &&
                    (!withinDirection || pending.rounding == LpRounding.DOWN)
                ) expired = true
                expired
            }
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
                SolveStatsSink("resumed-component-rounding"), LpSolveContext(factory)).use { engine ->
                engine.rootRoundingWarmStart = guidance
                assertNull(engine.lpRoundingProbe(objective, slice))
                val prefix = assertNotNull(guidance.pinning)
                assertTrue(expired)
                assertEquals(IntDomain(1, 1), prefix.intDomain(0))
                val paid = engine.primalPropagationWork

                val sample = assertNotNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))

                assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L), sample.ints.toList())
                assertEquals(prefix.work, engine.primalPropagationWork)
                assertTrue(engine.primalPropagationWork > paid)
                assertTrue(factory.calls.isEmpty())
            }
        }
    }

    @Test
    fun `declined later rounding components retain earlier values for complete search`() {
        val factors = (0 until 8).map { mask ->
            val coefficients = IntArray(4) { v ->
                if (v == 0 || mask and (1 shl (v - 1)) == 0) 1 else -1
            }
            Linear(coefficients, IntArray(4) { it }, LinearOp.GE, 1 - coefficients.count { it < 0 })
        } + (0 until 16).map { mask ->
            val coefficients = IntArray(4) { v -> if (mask and (1 shl v) == 0) 1 else -1 }
            Linear(coefficients, IntArray(4) { 4 + it }, LinearOp.GE, 1 - coefficients.count { it < 0 })
        }
        val problem = Problem(0, 8, Array(8) { IntDomain(0, 1) }, factors)
        val objective = LinearObjective(intCoefficients = LongArray(8))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n)
        for (v in 0 until 8) {
            primal[relaxation.intColOf[v]] = when (v) {
                0 -> 0.25
                in 1..3 -> 0.4
                else -> 0.5
            }
        }
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("declined-component-rounding"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation, primal = primal,
                sourceProblem = problem, auxiliaryPoint = true)

            assertNull(engine.lpRoundingProbe(objective, ExecutionControl.Never))
            engine.restartRootPrimalTargets()
            val values = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), values)
            assertTrue(factory.calls.isEmpty())
            engine.clearRootPrimalTargets()
            assertEquals(listOf(0L, 1L), engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList())
        }
    }
}
