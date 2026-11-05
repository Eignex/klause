package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.ComposedSampleVerifier
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.bounding.LpRounding
import com.eignex.klause.lp.bounding.RootRoundingWarmStart
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimalHeuristicsTest {

    @Test
    fun `masked component admission uses only known fractional targets`() {
        val problem = Problem(0, 8, Array(8) { IntDomain(0, if (it == 4) 2 else 1) },
            (0 until 2).flatMap { group ->
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) { if (group == 0) 2 else -2 }
                        else if (mask and (1 shl (v - 1)) == 0) 1 else -1
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
                assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
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
                assertTrue(engine.retainRootPrimalTargets(Cancellation.Never))
                val constructed = factory.calls.size

                val prepared = engine.prepareRootComponentTrial(objective, Cancellation.Never)

                assertEquals(kind == "fractional", prepared, kind)
                if (prepared) {
                    val prefix = assertNotNull(guide.pinning)
                    val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
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
                    model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
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
                assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
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

                val retained = engine.retainRootPrimalTargets(Cancellation.Never)
                val prepared = engine.prepareRootComponentTrial(objective, Cancellation.Never)

                assertEquals(funded, retained)
                assertEquals(funded, prepared)
                if (funded) {
                    val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))
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
            assertTrue(engine.retainRootPrimalTargets(Cancellation.Never))
            offered = 0L
            assertFalse(engine.prepareRootComponentTrial(objective, Cancellation.Never))
            assertNull(guide.pinning)
            assertEquals(0L, engine.primalPropagationWork)
            offered = 1_000_000L
            assertTrue(engine.prepareRootComponentTrial(objective, Cancellation.Never))
            val prefix = assertNotNull(guide.pinning)

            val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

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
            assertTrue(engine.retainRootPrimalTargets(Cancellation.Never))
            val order = assertNotNull(engine.prepareRootComponentOrder(session, Cancellation.Never))
            val targets = assertNotNull(engine.rootPrimalTargets)
            offered = 0L
            assertNull(engine.prepareRootComponentRounding(targets, order, Cancellation.Never))
            offered = 1_000_000L

            assertNotNull(engine.prepareRootComponentRounding(targets, order, Cancellation.Never))
            val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

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
                        if (v == 0) { if (group == 0) 1 else -1 }
                        else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                        (if (group == 0) 1 else -1) - coefficients.drop(1).count { it < 0 })
                }
            })
        val objective = LinearObjective(intCoefficients = LongArray(8))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n)
        for (v in 0 until 8) primal[relaxation.intColOf[v]] =
            when (v) { 0 -> 0.25; 4 -> 1.75; in 1..3 -> 0.4; else -> 0.6 }
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("independent-rounding"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation, primal = primal,
                sourceProblem = problem, auxiliaryPoint = true)

            val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

            assertEquals(listOf(1L, 1L, 1L, 1L, 1L, 0L, 0L, 0L), sample.ints.toList())
            val verifier = ComposedSampleVerifier(problem, objective, Cancellation.Never)
            assertIs<Verification.Accepted<Sample, Double>>(
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
                assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
                engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
                val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
                val primal = DoubleArray(relaxation.model.n)
                val known = BooleanArray(primal.size)
                for (v in 0 until 2 * width) {
                    val column = relaxation.intColOf[v]
                    known[column] = v % width == 0 || v % width == 4
                    primal[column] = when (v % width) { 0 -> 1.0; 4 -> 0.25; else -> 123.75 }
                }
                val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal,
                    knownColumns = known, sourceProblem = problem)
                val before = engine.primalPropagationWork
                val limit = Cancellation { engine.primalPropagationWork - before >= 200_000L }
                var expired = false
                var retreatDepth = -1
                var pinning: PropagationSession? = null
                val slice = Cancellation {
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
                assertIs<Verification.Accepted<Sample, Double>>(
                    ComposedSampleVerifier(problem, objective, Cancellation.Never)
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
        val token = Cancellation {
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
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val primal = DoubleArray(relaxation.model.n)
            val known = BooleanArray(primal.size)
            for (v in 0 until 8) {
                val column = relaxation.intColOf[v]
                known[column] = v % 4 < 2
                primal[column] = when (v % 4) { 0 -> 1.0; 1 -> 0.5; else -> 123.75 }
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal, knownColumns = known,
                sourceProblem = problem)
            var expired = false
            val slice = Cancellation {
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

            val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

            assertEquals(2L, sample.ints[0])
            assertEquals(2L, sample.ints[4])
            assertIs<Verification.Accepted<Sample, Double>>(
                ComposedSampleVerifier(problem, objective, Cancellation.Never)
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
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val relaxation = assertNotNull(engine.rootRoundingWarmStart).relaxation
            val primal = DoubleArray(relaxation.model.n)
            val known = BooleanArray(primal.size)
            for (v in 0 until 8) {
                val column = relaxation.intColOf[v]
                known[column] = v % 4 < 2
                primal[column] = when (v % 4) { 0 -> 1.0; 1 -> 0.5; else -> 123.75 }
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal, knownColumns = known,
                sourceProblem = problem)
            var expired = false
            val slice = Cancellation {
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

            engine.retainRootPartialPrimalGuidance(relaxation, Cancellation.Never)
            assertEquals(IntDomain(2, 2), assertNotNull(engine.rootRoundingWarmStart?.pinning).intDomain(0))
            val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

            assertEquals(2L, sample.ints[0])
            assertEquals(2L, sample.ints[4])
            assertIs<Verification.Accepted<Sample, Double>>(
                ComposedSampleVerifier(problem, objective, Cancellation.Never)
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
                            if (v == 0) { if (group == 0) 1 else -1 }
                            else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                        }
                        Linear(coefficients, IntArray(4) { 4 * group + it }, LinearOp.GE,
                            (if (group == 0) 1 else -1) - coefficients.drop(1).count { it < 0 })
                    }
                })
            val objective = LinearObjective(intCoefficients = LongArray(8))
            val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
            val primal = DoubleArray(relaxation.model.n)
            for (v in 0 until 8) primal[relaxation.intColOf[v]] =
                when (v) { 0 -> 0.25; 4 -> 1.75; in 1..3 -> 0.4; else -> 0.6 }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal,
                sourceProblem = problem, auxiliaryPoint = true)
            val factory = RecordingLpEngineFactory()
            var expired = false
            val slice = Cancellation {
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

                val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

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
        for (v in 0 until 8) primal[relaxation.intColOf[v]] = when (v) { 0 -> 0.25; in 1..3 -> 0.4; else -> 0.5 }
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("declined-component-rounding"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation, primal = primal,
                sourceProblem = problem, auxiliaryPoint = true)

            assertNull(engine.lpRoundingProbe(objective, Cancellation.Never))
            engine.restartRootPrimalTargets()
            val values = engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList()

            assertEquals(listOf(1L, 0L), values)
            assertTrue(factory.calls.isEmpty())
            engine.clearRootPrimalTargets()
            assertEquals(listOf(0L, 1L), engine.orderRootValues(VarRef.IntVar(0), sequenceOf(0L, 1L)).toList())
        }
    }

    @Test
    fun `ordinary integer rounding keeps nearest targets without a retained root plan`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            (0 until 8).map { mask ->
                val coefficients = IntArray(4) { v ->
                    if (v == 0 || mask and (1 shl (v - 1)) == 0) 1 else -1
                }
                Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                    1 - coefficients.drop(1).count { it < 0 })
            })
        val objective = LinearObjective(intCoefficients = LongArray(4))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n) { 0.4 }
        primal[relaxation.intColOf[0]] = 0.25
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("ordinary-nearest-rounding"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation, primal = primal)

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertNull(sample)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `completed declined directions wait for a fresh handoff without repeating propagation`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            (0 until 8).map { mask ->
                val coefficients = IntArray(3) { v -> if (mask and (1 shl v) == 0) 1 else -1 }
                Linear(coefficients, IntArray(3) { it }, LinearOp.GE,
                    1 - coefficients.count { it < 0 })
            })
        val objective = LinearObjective(intCoefficients = LongArray(3))
        val root = PropagationSession(problem)
        assertTrue(!root.isUnsatAtRoot)
        val relaxation = CpToLpRelaxation(problem, objective).build(root)
        val guidance = RootRoundingWarmStart(objective, relaxation,
            primal = DoubleArray(relaxation.model.n) { 0.4 })
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("completed-rounding-directions"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            var changes = 0
            var paid = 0L
            val slice = Cancellation {
                if (engine.primalPropagationWork > paid) {
                    paid = engine.primalPropagationWork
                    changes++
                }
                changes >= 3
            }
            assertNull(engine.lpRoundingProbe(objective, slice))
            assertTrue(engine.rootRoundingInterrupted)
            val completedWork = engine.primalPropagationWork

            assertNull(engine.lpRoundingProbe(objective, Cancellation.Never))

            assertEquals(completedWork, engine.primalPropagationWork)
            assertNull(engine.rootRoundingWarmStart)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `interrupted source admission retries completed pins in the same direction`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = LongArray(2))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n)
        primal[relaxation.intColOf[0]] = 0.4
        primal[relaxation.intColOf[1]] = 0.6
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("pending-rounding-admission"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            var expired = false
            assertNull(engine.lpRoundingProbe(objective, Cancellation { expired }) {
                expired = true
                null
            })
            val prefix = assertNotNull(guidance.pinning)
            assertEquals(IntDomain(0, 0), prefix.intDomain(0))
            assertEquals(IntDomain(1, 1), prefix.intDomain(1))
            val paid = engine.primalPropagationWork

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
            assertEquals(paid, engine.primalPropagationWork)
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `completed declined integer rounding is charged before another direction starts`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) },
            (0 until 8).map { mask ->
                val coefficients = IntArray(4) { v ->
                    if (v == 0 || mask and (1 shl (v - 1)) == 0) 1 else -1
                }
                Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                    1 - coefficients.drop(1).count { it < 0 })
            })
        val objective = LinearObjective(intCoefficients = LongArray(4))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val primal = DoubleArray(relaxation.model.n) { 0.4 }
        primal[relaxation.intColOf[0]] = 0.25
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
        val factory = RecordingLpEngineFactory()
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("charged-rounding-direction"), LpSolveContext(factory)).use { engine ->
            engine.rootRoundingWarmStart = guidance
            val slice = Cancellation { engine.primalPropagationWork > 0L }

            assertNull(engine.lpRoundingProbe(objective, slice))

            assertTrue(engine.rootRoundingInterrupted)
            assertTrue(engine.primalPropagationWork > 0L)
            assertNull(guidance.pinning)
            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)
            assertEquals(List(4) { 1L }, assertNotNull(sample).ints.toList())
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `directed integer rounding escapes a declined nearest pin prefix without another lp query`() {
        for (upward in listOf(true, false)) {
            val upper = if (upward) 1L else 2L
            val problem = Problem(0, 4, arrayOf(IntDomain(0, upper),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (upward) 1 else -1
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                        (if (upward) 1 else -1) - coefficients.drop(1).count { it < 0 })
                })
            val objective = LinearObjective(intCoefficients = LongArray(4))
            val root = PropagationSession(problem)
            val relaxation = CpToLpRelaxation(problem, objective).build(root)
            val primal = DoubleArray(relaxation.model.n)
            for (v in 0 until 4) {
                primal[relaxation.intColOf[v]] = if (v == 0) {
                    if (upward) 0.25 else 1.75
                } else if (upward) 0.4 else 0.6
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
            val factory = RecordingLpEngineFactory()
            assertEquals(IntDomain(0, upper), root.intDomain(0))

            val sample = LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
                SolveStatsSink("directed-integer-rounding"), LpSolveContext(factory)).use { engine ->
                engine.rootRoundingWarmStart = guidance

                engine.lpRoundingProbe(objective, Cancellation.Never)
            }

            assertEquals(listOf(1L) + List(3) { if (upward) 1L else 0L }, assertNotNull(sample).ints.toList())
            assertTrue(factory.calls.isEmpty())
        }
    }

    @Test
    fun `interrupted directed integer rounding resumes its direction and completed pins`() {
        for (upward in listOf(true, false)) {
            val upper = if (upward) 1L else 2L
            val problem = Problem(0, 4, arrayOf(IntDomain(0, upper),
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                (0 until 8).map { mask ->
                    val coefficients = IntArray(4) { v ->
                        if (v == 0) {
                            if (upward) 1 else -1
                        } else if (mask and (1 shl (v - 1)) == 0) 1 else -1
                    }
                    Linear(coefficients, IntArray(4) { it }, LinearOp.GE,
                        (if (upward) 1 else -1) - coefficients.drop(1).count { it < 0 })
                })
            val objective = LinearObjective(intCoefficients = LongArray(4))
            val root = PropagationSession(problem)
            val relaxation = CpToLpRelaxation(problem, objective).build(root)
            val primal = DoubleArray(relaxation.model.n)
            for (v in 0 until 4) {
                primal[relaxation.intColOf[v]] = if (v == 0) {
                    if (upward) 0.25 else 1.75
                } else if (upward) 0.4 else 0.6
            }
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = primal)
            val factory = RecordingLpEngineFactory()
            assertEquals(IntDomain(0, upper), root.intDomain(0))
            var expired = false
            val slice = Cancellation {
                val pins = guidance.pinning
                if (pins != null && pins.decisionLevel >= 2 && pins.intDomain(0).min == 1L) expired = true
                expired
            }
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
                SolveStatsSink("resumed-directed-rounding"), LpSolveContext(factory)).use { engine ->
                engine.rootRoundingWarmStart = guidance
                assertNull(engine.lpRoundingProbe(objective, slice))
                val prefix = assertNotNull(guidance.pinning)
                assertTrue(expired)
                assertEquals(IntDomain(1, 1), prefix.intDomain(0))
                val paid = engine.primalPropagationWork

                val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

                assertEquals(listOf(1L) + List(3) { if (upward) 1L else 0L }, assertNotNull(sample).ints.toList())
                assertEquals(prefix.work, engine.primalPropagationWork)
                assertTrue(engine.primalPropagationWork > paid)
                assertTrue(factory.calls.isEmpty())
            }
        }
    }

    @Test
    fun `completed rounding pins resume under a fresh slice without repeated propagation charges`() {
        val n = 4
        val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
            Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
        ))
        val objective = LinearObjective(intCoefficients = LongArray(n) { 1L })
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = DoubleArray(relaxation.model.n))
        var expired = false
        val slice = Cancellation {
            if ((guidance.pinning?.decisionLevel ?: 0) > 0) expired = true
            expired
        }
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("resumed-rounding-pins")).use { engine ->
            engine.rootRoundingWarmStart = guidance
            assertNull(engine.lpRoundingProbe(objective, slice))
            val prefix = assertNotNull(guidance.pinning)
            assertTrue(prefix.probeCancelled)
            assertEquals(IntDomain(0, 0), prefix.intDomain(0))
            val paid = engine.primalPropagationWork
            assertTrue(paid > 0L)
            assertNull(engine.lpRoundingProbe(objective, Cancellation { true }))
            assertEquals(paid, engine.primalPropagationWork)

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertTrue(expired)
            assertEquals(List(n) { 0L }, assertNotNull(sample).ints.toList())
            assertEquals(prefix.work, engine.primalPropagationWork)
            assertTrue(engine.primalPropagationWork > paid)
            assertNull(engine.rootRoundingWarmStart)
        }
    }

    @Test
    fun `changed rounding guidance or objective discards earlier completed pins`() {
        for (changedObjective in listOf(false, true)) {
            val n = 4
            val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
                Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
            ))
            val objective = LinearObjective(intCoefficients = LongArray(n) { 1L })
            val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
            val guidance = RootRoundingWarmStart(objective, relaxation, primal = DoubleArray(relaxation.model.n))
            val slice = Cancellation { (guidance.pinning?.decisionLevel ?: 0) > 0 }
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink("changed-rounding-pins")).use { engine ->
                engine.rootRoundingWarmStart = guidance
                assertNull(engine.lpRoundingProbe(objective, slice))
                assertEquals(IntDomain(0, 0), assertNotNull(guidance.pinning).intDomain(0))
                val next = if (changedObjective) LinearObjective(intCoefficients = LongArray(n) { 2L }) else objective
                val layout = CpToLpRelaxation(problem, next).build(PropagationSession(problem))
                engine.rootRoundingWarmStart = RootRoundingWarmStart(next, layout,
                    primal = DoubleArray(layout.model.n) { 1.0 })

                val sample = engine.lpRoundingProbe(next, Cancellation.Never)

                assertEquals(List(n) { 1L }, assertNotNull(sample).ints.toList())
            }
        }
    }

    @Test
    fun `rounding rebuilds initial propagation interrupted before a complete root`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val guidance = RootRoundingWarmStart(objective, relaxation, primal = DoubleArray(relaxation.model.n))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("interrupted-rounding-root")).use { engine ->
            engine.rootRoundingWarmStart = guidance
            assertNull(engine.lpRoundingProbe(objective, Cancellation { true }))
            assertNull(guidance.pinning)

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
        }
    }

    @Test
    fun `masked rounding guidance respects source domains and unknown columns`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val relaxation = CpToLpRelaxation(problem, objective).build(PropagationSession(problem))
        val known = BooleanArray(relaxation.model.n)
        known[relaxation.intColOf[0]] = true

        for ((target, expected) in listOf(-1.0 to listOf(0L, 1L), 1.0 to listOf(1L, 0L))) {
            val guide = DoubleArray(relaxation.model.n) { 1.0 }
            guide[relaxation.intColOf[0]] = target
            val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink("masked-rounding-guidance")).use { engine ->
                engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                    primal = guide, knownColumns = known)

                engine.lpRoundingProbe(objective, Cancellation.Never)
            }

            assertEquals(expected, assertNotNull(sample).ints.toList())
        }
    }

    @Test
    fun `unknown binary rounding columns try the other value after a conflicting zero pin`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 0),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 1))
        val session = PropagationSession(problem)
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        val known = BooleanArray(relaxation.model.n)
        known[relaxation.intColOf[2]] = true
        val guide = DoubleArray(relaxation.model.n) { 1.0 }
        guide[relaxation.intColOf[2]] = 0.0
        assertEquals(IntDomain(0, 1), session.intDomain(0))

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("unknown-binary-rounding")).use { engine ->
            engine.rootRoundingWarmStart = RootRoundingWarmStart(objective, relaxation,
                primal = guide, knownColumns = known)
            engine.lpRoundingProbe(objective, Cancellation.Never)
        }

        assertEquals(listOf(1L, 0L, 0L), assertNotNull(sample).ints.toList())
    }

    @Test
    fun `an optimal rounding point survives interruption while its solver closes`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var stopped = false
        var solves = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel, cancellation: Cancellation, iterationLimit: Int, workLimit: Long,
                trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): TableauCutSolver {
                solves++
                val delegate = ProductionLpEngineFactory.newTableauSolver(
                    model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : TableauCutSolver by delegate {
                    override fun close() {
                        delegate.close()
                        stopped = true
                    }
                }
            }
        }
        val token = Cancellation { stopped }
        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true)),
            SolveStatsSink("closed-rounding-point"), LpSolveContext(factory),
            nodeWorkAllowance = { if (stopped) 0L else 100_000L }).use { engine ->
            assertNull(engine.lpRoundingProbe(objective, token))
            assertTrue(engine.rootRoundingInterrupted)
            stopped = false

            val sample = engine.lpRoundingProbe(objective, token)

            assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
            assertEquals(1, solves)
        }
    }

    @Test
    fun `completed rounding LP resumes without solving its original objective again`() {
        for (sameObjective in listOf(true, false)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            val factory = RecordingLpEngineFactory()
            var stopped = false
            val token = Cancellation { stopped }

            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
                SolveStatsSink("resumed-rounding-point"), LpSolveContext(factory)).use { engine ->
                val first = engine.lpRoundingProbe(objective, token, accept = {
                    stopped = true
                    null
                })
                assertNull(first)
                assertTrue(engine.rootRoundingInterrupted)
                val constructed = factory.calls.size
                stopped = false
                val next = if (sameObjective) objective else LinearObjective(intCoefficients = longArrayOf(2, 1))

                val sample = engine.lpRoundingProbe(next, token)

                val expected = if (sameObjective) listOf(1L, 0L) else listOf(0L, 1L)
                assertEquals(expected, assertNotNull(sample).ints.toList())
                if (sameObjective) assertEquals(constructed, factory.calls.size)
                else assertTrue(factory.calls.size > constructed)
            }
        }
    }

    @Test
    fun `completed root guidance avoids another rounding LP solve`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val factory = RecordingLpEngineFactory()

        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("root-guidance"), LpSolveContext(factory)).use { engine ->
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            val constructed = factory.calls.size

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
            assertEquals(constructed, factory.calls.size)
        }
    }

    @Test
    fun `rounding reads returned child coordinates after the query invocation expires`() {
        for ((clearView, cancelChild) in listOf(
            false to false, false to true, true to false, true to true,
        )) {
            val problem = Problem(0, 7, Array(7) { IntDomain(0, 1) }, listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(3, 4), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(5, 6), LinearOp.GE, 1),
            )).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 2, 1, 2))
            var expired = false
            var childCalls = 0
            val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                            if (cancelChild && childCalls == 2) expired = true
                            return delegate.resolveBounds(LpFloatAllowance(allowance?.work ?: 0L, 1)).also {
                                if (++childCalls == 3) expired = true
                            }
                        }
                    }
                }
            })
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), SolveStatsSink("deferred-child-guidance"),
                LpSolveContext(factory)).use { engine ->
                assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
                val token = Cancellation { expired }
                val bound = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), token)
                val queries = factory.floatAllowances.size
                val constructions = factory.calls.size
                assertTrue(expired)
                assertTrue(bound.isNaN())
                assertTrue(queries > 0)

                if (clearView) engine.cpAdapter.retract(0)

                val sample = assertNotNull(engine.lpRoundingProbe(objective, Cancellation.Never))

                assertEquals(queries, factory.floatAllowances.size)
                assertEquals(constructions, factory.calls.size)
                assertTrue(sample.ints.all { it in 0L..1L } &&
                    listOf(0 to 1, 1 to 2, 0 to 2, 3 to 4, 5 to 6).all { (left, right) ->
                        sample.ints[left] + sample.ints[right] >= 1L
                    })
            }
        }
    }

    @Test
    fun `rounding reuses prepared components after a cancelled root query`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1),
        )).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 1, 2))
        val factory = RecordingLpEngineFactory()

        LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("prepared-component-rounding"),
            LpSolveContext(factory)).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList(), Cancellation { true })
            val constructed = factory.calls.size

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertEquals(listOf(1L, 0L, 1L, 0L), assertNotNull(sample).ints.toList())
            assertEquals(constructed, factory.calls.size)
        }
    }

    @Test
    fun `prepared component rounding respects a changed objective`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))

        val sample = LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                rootCutHarvest = false)), SolveStatsSink("changed-prepared-rounding")).use { engine ->
            assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))

            engine.lpRoundingProbe(LinearObjective(intCoefficients = longArrayOf(2, 1)), Cancellation.Never)
        }

        assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
    }

    @Test
    fun `resumed rounding owns its guidance independently of the live solver`() {
        for (stopDuringPinning in listOf(false, true)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
                listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1))).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var ownerPoint: DoubleArray? = null
            var stopped = false
            var activeEngine: LpEngine? = null
            val token = Cancellation {
                if (stopDuringPinning && (activeEngine?.rootRoundingWarmStart?.pinning?.decisionLevel ?: 0) > 0) {
                    stopped = true
                }
                stopped
            }
            val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newPersistentSolver(
                    model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                    workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
                ): PersistentLpSolver {
                    val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                        refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                    return object : PersistentLpSolver by delegate {
                        override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? =
                            delegate.resolveBounds(allowance)?.also { ownerPoint = it.primal }
                    }
                }
            })
            LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, componentOwnership = true,
                    rootCutHarvest = false)), SolveStatsSink("owned-rounding-guidance"),
                LpSolveContext(factory)).use { engine ->
                activeEngine = engine
                assertTrue(engine.prepareRootLayout(Cancellation.Never, Cancellation.Never))
                assertNull(engine.lpRoundingProbe(objective, token, accept = {
                    stopped = true
                    null
                }))
                val prefix = engine.rootRoundingWarmStart?.pinning
                if (stopDuringPinning) {
                    assertTrue(assertNotNull(prefix).decisionLevel > 0)
                }
                val queries = factory.floatAllowances.size
                val constructions = factory.calls.size
                assertNotNull(ownerPoint).fill(1.0)

                val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

                assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
                assertEquals(queries, factory.floatAllowances.size)
                assertEquals(constructions, factory.calls.size)
                if (stopDuringPinning) assertEquals(assertNotNull(prefix).work, engine.primalPropagationWork)
            }
        }
    }

    @Test
    fun `retained root guidance still requires consumer acceptance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var offered = 0

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("declined-root-guidance")).use { engine ->
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            engine.lpRoundingProbe(objective, Cancellation.Never, accept = {
                offered++
                null
            })
        }

        assertEquals(1, offered)
        assertNull(sample)
    }

    @Test
    fun `changed objective replaces completed root guidance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("changed-root-guidance")).use { engine ->
            engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())
            engine.lpRoundingProbe(LinearObjective(intCoefficients = longArrayOf(2, 1)), Cancellation.Never)
        }

        assertEquals(listOf(0L, 1L), assertNotNull(sample).ints.toList())
    }

    @Test
    fun `refused root certificates grant no bound authority to rounding guidance`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        val context = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })

        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("uncertified-root-guidance"), context).use { engine ->
            val lower = engine.rootLpRelaxationBound(assertNotNull(engine.lpRelaxer), emptyList())

            val sample = engine.lpRoundingProbe(objective, Cancellation.Never)

            assertTrue(lower.isNaN())
            assertEquals(listOf(1L, 0L), assertNotNull(sample).ints.toList())
        }
    }

    @Test
    fun `LP pinning should withhold a proposal when one cumulative fire is interrupted`() {
        val n = 32
        val problem = Problem(0, n, Array(n) { IntDomain(0, 100L + it) }, arrayOf<Factor>(
            Cumulative(IntArray(n) { it }, LongArray(n) { 1L }, LongArray(n) { 1L }, n.toLong()),
        ))
        val objective = LinearObjective(intCoefficients = LongArray(n) { 1L })
        var armed = false
        var polls = 0
        val session = PropagationSession(problem, Cancellation { armed && ++polls >= 4 })
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        armed = true

        val proposal = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "interrupted-cumulative-pinning")).use { engine ->
            engine.pinToward(session, relaxation) { 0.0 }
        }

        assertNull(proposal)
        assertTrue(session.probeCancelled)
        assertEquals(4, polls)
        assertEquals(IntDomain(0, 101), session.intDomain(1))
    }

    @Test
    fun `LP pinning should stop before another mutation after an interrupted equality`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 255) }, emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        var armed = false
        lateinit var session: PropagationSession
        session = PropagationSession(problem, Cancellation {
            armed && session.litTruth(session.boundGeLit(0, 16, true)) == true
        })
        repeat(128) { session.boundGeLit(0, it.toLong() + 1, true) }
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        armed = true

        val proposal = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "interrupted-pinning")).use { engine ->
            engine.pinToward(session, relaxation) { 129.0 }
        }

        assertNull(proposal)
        assertTrue(session.probeCancelled)
        assertEquals(IntDomain(0, 255), session.intDomain(1))
    }

    @Test
    fun `interrupted rounding should resume only its original LP objective`() {
        for (sameObjective in listOf(true, false)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ))
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var stopped = false
            var solves = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val first = solves++ == 0
                    val delegate = ProductionLpEngineFactory.newTableauSolver(
                        model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing,
                    )
                    return object : TableauCutSolver by delegate {
                        override val lastTermination get() =
                            if (first) LpFloatTermination.CANCELLED else delegate.lastTermination
                        override fun solve(warm: Basis?): FloatLpResult? {
                            if (!first) return if (warm == null) null else delegate.solve(warm)
                            val result = delegate.solve(warm) ?: return null
                            stopped = true
                            return FloatLpResult(result.basis, result.objective, result.duals, result.primal,
                                optimal = false)
                        }
                    }
                }
            }
            val sink = SolveStatsSink(backend = "resumed-rounding")
            val token = Cancellation { stopped }

            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink,
                LpSolveContext(factory)).use { engine ->
                assertNull(engine.lpRoundingProbe(objective, token))
                stopped = false
                val next = if (sameObjective) objective else LinearObjective(intCoefficients = longArrayOf(2, 1))

                val proposal = engine.lpRoundingProbe(next, token)

                if (sameObjective) {
                    assertEquals(listOf(1L, 0L), assertNotNull(proposal).ints.toList())
                } else {
                    assertNull(proposal)
                }
            }
        }
    }

    @Test
    fun `admitted pump rounds should reuse root assembly with private objective owners`() {
        for (mode in LpSourcePreparation.entries) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf<Factor>(
                Linear(IntArray(4) { 1 }, IntArray(4) { it }, LinearOp.GE, 1),
                Linear(IntArray(4) { 1 }, IntArray(4) { it }, LinearOp.LE, 3))).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 3, 4), constant = 17L)
            val models = ArrayList<LpModel>()
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    models += model
                    return ProductionLpEngineFactory.newTableauSolver(
                        model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing)
                }
            }
            val sink = SolveStatsSink("pump-assembly-reuse")
            var offered = 0

            val sample = LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, sourcePreparation = mode)), sink,
                LpSolveContext(factory)).use { engine ->
                engine.lpFeasibilityPump(objective, Cancellation.Never) { it.takeIf { ++offered == 3 } }
            }

            assertTrue(assertNotNull(sample).ints.sum() >= 1L)
            assertEquals(3, models.size)
            assertNotSame(models[0].cost, models[1].cost)
            assertNotSame(models[1].cost, models[2].cost)
            assertEquals(listOf(1L, 2L, 3L, 4L, 0L, 0L), models[0].cost.toList())
            val assembly = assertNotNull(sink.snapshot().lp.phases["PRIMAL_LP_ASSEMBLY_ROOT"])
            assertEquals(if (mode == LpSourcePreparation.ADMITTED) 1L else 3L, assembly.outcomes["BUILT"])
            assertEquals(if (mode == LpSourcePreparation.ADMITTED) 2L else null, assembly.outcomes["REUSED"])
            assertTrue(assembly.work > 0L)
            if (mode == LpSourcePreparation.ADMITTED) {
                val admission = assertNotNull(sink.snapshot().lp.phases["PRIMAL_LP_TEMPLATE_ROOT"])
                assertEquals(1L, admission.outcomes["ADMITTED"])
            }
        }
    }

    @Test
    fun `changed shared root domains should retire pump preparation`() {
        for (hole in listOf(false, true)) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, if (hole) 2 else 1) }, arrayOf<Factor>(
                Linear(IntArray(4) { 1 }, IntArray(4) { it }, LinearOp.GE, 1),
                Linear(IntArray(4) { 1 }, IntArray(4) { it }, LinearOp.LE, if (hole) 7 else 3))).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 3, 4))
            val sink = SolveStatsSink("changed-pump-root")
            var offered = 0

            val sample = LpEngine(problem, objective,
                LpParams(lpPlan = LpPlan(bounding = true, sourcePreparation = LpSourcePreparation.ADMITTED)),
                sink).use { engine ->
                engine.lpFeasibilityPump(objective, Cancellation.Never) {
                    if (++offered == 1) {
                        problem.rootIntDomainsInPlace[0] = if (hole) {
                            IntDomain(0, 2).excludeValue(1)
                        } else IntDomain(0, 0)
                        null
                    } else it
                }
            }

            val value = assertNotNull(sample).ints[0]
            if (hole) assertNotEquals(1L, value) else assertEquals(0L, value)
            val assembly = assertNotNull(sink.snapshot().lp.phases["PRIMAL_LP_ASSEMBLY_ROOT"])
            assertEquals(1L, assembly.outcomes["SOURCE_CHANGED"])
            assertEquals(2L, assembly.outcomes["BUILT"])
        }
    }

    @Test
    fun `cancelled pump objective preparation should not open another numerical owner`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf<Factor>(
            Linear(IntArray(4) { 1 }, IntArray(4) { it }, LinearOp.GE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2, 3, 4))
        val factory = RecordingLpEngineFactory()
        val sink = SolveStatsSink("cancelled-pump-objective")
        var armed = false
        var stopped = false
        val token = object : Cancellation {
            private val meter = WorkMeter { if (armed) stopped = true }
            override fun isCancelled(): Boolean = stopped
            override fun workMeter(): WorkMeter = meter
        }

        val sample = LpEngine(problem, objective,
            LpParams(lpPlan = LpPlan(bounding = true, sourcePreparation = LpSourcePreparation.ADMITTED)), sink,
            LpSolveContext(factory)).use { engine ->
            engine.lpFeasibilityPump(objective, token) {
                armed = true
                null
            }
        }

        assertNull(sample)
        assertEquals(1, factory.calls.size)
        val assembly = assertNotNull(sink.snapshot().lp.phases["PRIMAL_LP_ASSEMBLY_ROOT"])
        assertEquals(1L, assembly.outcomes["CANCELLED"])
        assertTrue(assembly.work > 0L)
    }

    @Test
    fun `the pump should change an integral rounding the consumer declines`() {
        for (coefficient in listOf(1L, -1L)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(coefficient))
            val sink = SolveStatsSink(backend = "pump-test")
            var declined: Long? = null

            val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
                assertNotNull(
                    engine.lpFeasibilityPump(objective, Cancellation.Never) { sample ->
                        if (declined == null) declined = sample.ints.single()
                        sample.takeIf { it.ints.single() != declined }
                    },
                )
            }

            assertNotEquals(declined, sample.ints.single())
        }
    }

    @Test
    fun `the pump should explore from an initial discrete target`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val initial = Sample(BooleanArray(0), longArrayOf(1, 1))
        val proposed = ArrayList<List<Long>>()
        val sink = SolveStatsSink(backend = "pump-test")

        val sample = LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            assertNotNull(
                engine.lpFeasibilityPump(objective, Cancellation.Never, initial = initial) { sample ->
                    proposed += sample.ints.toList()
                    sample.takeIf { it.ints.toList() != initial.ints.toList() }
                },
            )
        }

        assertEquals(initial.ints.toList(), proposed.first())
        assertNotEquals(initial.ints.toList(), sample.ints.toList())
    }

    @Test
    fun `LP primal probes should not offer a stopped relaxation iterate`() {
        for (pump in listOf(false, true)) {
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val sink = SolveStatsSink(backend = "stopped-primal")
            var offered = 0
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val delegate = ProductionLpEngineFactory.newTableauSolver(
                        model,
                        cancellation,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : TableauCutSolver by delegate {
                        override val lastTermination = LpFloatTermination.WORK
                        override fun solve(warm: Basis?): FloatLpResult? = delegate.solve(warm)?.let { result ->
                            FloatLpResult(result.basis, result.objective, result.duals, result.primal, optimal = false)
                        }
                        override fun solvePrimal(warm: Basis?): FloatLpResult? = solve(warm)
                    }
                }
            }

            val proposal = LpEngine(
                problem,
                objective,
                LpParams(lpPlan = LpPlan(bounding = true)),
                sink,
                LpSolveContext(factory),
            ).use { engine ->
                if (pump) {
                    engine.lpFeasibilityPump(objective, Cancellation.Never) {
                        offered++
                        it
                    }
                } else {
                    engine.lpRoundingProbe(objective, Cancellation.Never) {
                        offered++
                        it
                    }
                }
            }

            assertNull(proposal)
            assertEquals(0, offered)
            assertTrue(sink.snapshot().lp.rootPasses.sum > 0.0)
        }
    }

    @Test
    fun `primal fallback should open its dual solve with the remaining root allowance`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val limits = ArrayList<Long>()
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newTableauSolver(
                model: LpModel,
                cancellation: Cancellation,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): TableauCutSolver {
                limits += workLimit
                val delegate = ProductionLpEngineFactory.newTableauSolver(
                    model,
                    cancellation,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : TableauCutSolver by delegate {
                    private var spent = 0L
                    override val lastMetrics get() = LpSolveMetrics(workOps = spent)
                    override fun solvePrimal(warm: Basis?): FloatLpResult? {
                        spent = 5_000L
                        return null
                    }
                }
            }
        }
        val plan = LpPlan(bounding = true, rootMaxWork = 12_000L)
        val sink = SolveStatsSink(backend = "primal-fallback")

        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = plan, solveBudgetMillis = 4L),
            sink,
            LpSolveContext(factory),
        ).use { engine ->
            assertNotNull(engine.lpFeasibilityPump(objective, Cancellation.Never))
        }

        val assembly = assertNotNull(sink.snapshot().lp.phases["PRIMAL_LP_ASSEMBLY_ROOT"]).work
        assertTrue(assembly > 0L)
        assertEquals(listOf(12_000L - assembly, 7_000L - assembly), limits)
    }

    @Test
    fun `directed rounding should reuse its LP solve after a declined mixed proposal`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 5)),
            arrayOf<Factor>(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(0),
                    doubleArrayOf(-1.0),
                    LinearOp.GE,
                    0.0,
                ),
                Linear(
                    IntArray(0),
                    DoubleArray(0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.GE,
                    0.4,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val sink = SolveStatsSink(backend = "rounding-test")
        val proposed = ArrayList<Long>()

        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), sink).use { engine ->
            assertNotNull(
                engine.lpRoundingProbe(objective, Cancellation.Never) { sample ->
                    proposed += sample.ints.single()
                    sample.takeIf { it.ints.single() == 1L }
                },
            )
        }

        assertEquals(listOf(0L, 1L), proposed)
        assertEquals(1.0, sink.snapshot().lp.rootPasses.sum)
    }

    private val producers: List<Pair<String, LpEngine.(LinearObjective, Cancellation) -> Sample?>> = listOf(
        "the rounding probe" to { objective, token -> lpRoundingProbe(objective, token) },
        "the feasibility pump" to { objective, token -> lpFeasibilityPump(objective, token) },
        "the best-bound tree search" to { objective, token -> lbTreeSearch(objective, token)?.sample },
    )

    private fun instance(rng: Random): Pair<Problem, LinearObjective> {
        val n = rng.nextInt(3, 7)
        val factors = ArrayList<Factor>()
        repeat(rng.nextInt(1, 4)) {
            val k = rng.nextInt(2, n + 1)
            val vars = (0 until n).shuffled(rng).take(k).toIntArray()
            val coeffs = IntArray(k) { 1 }
            if (rng.nextBoolean()) {
                factors.add(Linear(coeffs, vars, LinearOp.GE, 1))
            } else {
                factors.add(Linear(coeffs, vars, LinearOp.LE, k - 1))
            }
        }
        val problem = Problem(0, n, Array(n) { IntDomain(0, 1) }, factors.toTypedArray())
        return problem to LinearObjective(intCoefficients = LongArray(n) { rng.nextLong(-2, 3) })
    }

    @Test
    fun `every LP primal proposal should pass composed verification`() {
        val rng = Random(20260902)
        val produced = producers.associate { (name, _) -> name to 0 }.toMutableMap()
        repeat(6) {
            val (problem, objective) = instance(rng)
            val engine = LpEngine(
                problem,
                objective,
                LpParams(lpPlan = LpPlan(bounding = true, probe = true, lbTreeSearch = true)),
                SolveStatsSink(backend = "lp-proposal-test"),
            )
            val verifier = ComposedSampleVerifier(problem, objective, Cancellation.Never)
            for ((name, produce) in producers) {
                val proposal = engine.produce(objective, Cancellation.Never) ?: continue
                produced[name] = produced.getValue(name) + 1
                assertIs<Verification.Accepted<Sample, Double>>(
                    verifier.verify(Candidate(proposal, objective.evaluate(proposal))),
                    "$name proposed an assignment the composed model refuses",
                )
            }
        }
        assertTrue(produced.values.all { it > 0 }, "a producer seeded nothing to verify: $produced")
    }
}
