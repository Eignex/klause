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
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimalHeuristicsTest {
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
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true, probe = true)),
            SolveStatsSink("closed-rounding-point"), LpSolveContext(factory)).use { engine ->
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

        assertEquals(listOf(12_000L, 7_000L), limits)
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
