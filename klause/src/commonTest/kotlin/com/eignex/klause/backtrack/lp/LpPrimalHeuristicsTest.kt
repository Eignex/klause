package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimalHeuristicsTest {
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
                    cancellation: ExecutionControl,
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
                engine.lpFeasibilityPump(objective, ExecutionControl.Never) { it.takeIf { ++offered == 3 } }
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
                engine.lpFeasibilityPump(objective, ExecutionControl.Never) {
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
        val token = object : ExecutionControl() {
            private val meter = WorkMeter { if (armed) stopped = true }
            override fun isCancelled(): Boolean = stopped
            override val accounting: WorkMeter get() = meter
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
                    engine.lpFeasibilityPump(objective, ExecutionControl.Never) { sample ->
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
                engine.lpFeasibilityPump(objective, ExecutionControl.Never, initial = initial) { sample ->
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
                    cancellation: ExecutionControl,
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
                    engine.lpFeasibilityPump(objective, ExecutionControl.Never) {
                        offered++
                        it
                    }
                } else {
                    engine.lpRoundingProbe(objective, ExecutionControl.Never) {
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
                cancellation: ExecutionControl,
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
            assertNotNull(engine.lpFeasibilityPump(objective, ExecutionControl.Never))
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
                engine.lpRoundingProbe(objective, ExecutionControl.Never) { sample ->
                    proposed += sample.ints.single()
                    sample.takeIf { it.ints.single() == 1L }
                },
            )
        }

        assertEquals(listOf(0L, 1L), proposed)
        assertEquals(1.0, sink.snapshot().lp.rootPasses.sum)
    }

    private val producers: List<Pair<String, LpEngine.(LinearObjective, ExecutionControl) -> Sample?>> = listOf(
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
            val verifier = finiteWitnessVerifier(problem.bake(), objective)
            for ((name, produce) in producers) {
                val proposal = engine.produce(objective, ExecutionControl.Never) ?: continue
                produced[name] = produced.getValue(name) + 1
                assertIs<Verification.Accepted<*, *>>(
                    verifier.verify(Candidate(proposal, objective.evaluate(proposal))),
                    "$name proposed an assignment the composed model refuses",
                )
            }
        }
        assertTrue(produced.values.all { it > 0 }, "a producer seeded nothing to verify: $produced")
    }

    @Test
    fun `pinned leaves should obey the session inert relation checks`() {
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "Original source certification enforces the clause.",
                ) { NoPropagator }
            },
        )
        val problem = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake(providers)
        for ((first, admitted) in listOf(false to false, true to true)) {
            val session = PropagationSession(problem, ExecutionControl.Never)
            session.pinBool(0, first)
            session.pinBool(1, false)

            val leaf = pinnedLeaf(session)

            assertEquals(admitted, leaf != null)
        }
    }
}
