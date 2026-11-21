package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.RepairEndpoint
import com.eignex.klause.localsearch.RepairTrace
import com.eignex.klause.meta.alns.AlnsTestFixtures.NoFeasibleLs
import com.eignex.klause.meta.alns.AlnsTestFixtures.SELECT_DEAREST
import com.eignex.klause.meta.alns.AlnsTestFixtures.SELECT_WEIGHTS
import com.eignex.klause.meta.alns.AlnsTestFixtures.bootstrapAt
import com.eignex.klause.meta.alns.AlnsTestFixtures.chainProblem
import com.eignex.klause.meta.alns.AlnsTestFixtures.chainSweep
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectAlns
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectExchangeHolding
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectObjective
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectOptimum
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectProblem
import com.eignex.klause.meta.alns.AlnsTestFixtures.starvedBootstrap
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.DeducedRestrictions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.ExecutionControl
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.util.numbers.BigFraction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlnsTest {
    @Test
    fun `accepted nonbest centers should retain the standing best cutoff`() {
        val problem = selectProblem()
        val best = selectOptimum()
        val worse = Sample(BooleanArray(SELECT_WEIGHTS.size) { true }, LongArray(0))
        val trace = RepairTrace("ALNS accepted nonbest centers")
        val alns = Alns(inner = bootstrapAt(problem, best),
            destroyOperators = listOf(DestroyOperator.Random), repairOperators = listOf(RepairOperator { worse }),
            acceptance = AcceptanceCriterion.RandomWalk, minDestroyFraction = 1.0, maxDestroyFraction = 1.0,
            maxIterations = 2, flipsPerIteration = 1)
        alns.repairTrace = trace

        alns.minimize(selectObjective, LocalSearchParams(initialAssignment = best, randomSeed = 7))

        val attempt = trace.attempts.last()
        assertEquals(worse, attempt.center)
        assertEquals(best, attempt.bestSample)
        assertEquals(selectObjective.evaluate(best), attempt.bestObjectiveCutoff)
        assertEquals(selectObjective.evaluateExact(best), attempt.exactBestObjective)
        assertTrue(attempt.scopeComplete)
    }

    @Test
    fun `available incumbents should start repair without bootstrapping`() {
        val problem = selectProblem()
        val seed = Sample(BooleanArray(SELECT_WEIGHTS.size) { true }, LongArray(0))
        val improved = selectOptimum()
        for (retained in listOf(false, true)) {
            for (shared in listOf(false, true)) {
                var repairs = 0
                val inner = object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
                    override fun minimize(objective: LinearObjective, params: LocalSearchParams) = error("bootstrap")
                }
                val alns = Alns(
                    inner = inner,
                    pooledIncumbents = selectExchangeHolding(seed).takeIf { shared },
                    destroyOperators = listOf(DestroyOperator.Random),
                    repairOperators = listOf(
                        RepairOperator {
                            repairs++
                            assertEquals(seed, it.incumbent)
                            improved
                        },
                    ),
                    minDestroyFraction = 1.0,
                    maxDestroyFraction = 1.0,
                    maxIterations = 1,
                    flipsPerIteration = 8,
                )
                val params = LocalSearchParams(initialAssignment = seed.takeUnless { shared }, maxInstructions = 8)
                val trace = RepairTrace("ALNS repair return; no descent classification")
                alns.repairTrace = trace

                val result = if (retained) {
                    alns.resumable(selectObjective, params).use {
                        assertNotNull(it.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {})
                    }
                } else {
                    alns.minimize(selectObjective, params)
                }

                assertEquals(improved, result.assignment)
                assertEquals(1, repairs)
                assertEquals(0L, result.stats.alns.bootstrapCpNodes)
                assertEquals(0L, result.stats.alns.bootstrapLsMoves)
                assertEquals(8L, result.stats.alns.outerAllowance)
                val attempt = trace.attempts.single()
                assertEquals(seed, attempt.center)
                assertEquals(RepairEndpoint.Repaired, attempt.endpoint)
                assertEquals(improved, attempt.frames.last().sample)
                assertTrue(attempt.effectiveBools.contentEquals(attempt.requestedBools))
            }
        }
    }

    @Test
    fun `session handles should preserve offered instruction charges`() {
        val seed = selectOptimum()
        val alns = Alns(NoFeasibleLs(selectProblem()), maxIterations = 0)
        alns.session().use { session ->
            val handle = assertIs<InstructionSlicedSearch>(
                session.resumable(
                    selectObjective,
                    LocalSearchParams(initialAssignment = seed),
                ),
            )

            val result = handle.use {
                assertNotNull(it.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
            }

            assertEquals(50L, handle.chargedInstructions)
            assertEquals(0.0, result.stats.ls.moves.sum)
            assertEquals(seed, result.assignment)
        }
    }

    @Test
    fun `a mixed seed should reach an improving exact source witness through neighborhood repair`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1), realCoefficients = doubleArrayOf(10.0))
        val seed = Sample(BooleanArray(0), longArrayOf(0), doubleArrayOf(1.0), listOf(BigFraction.ONE))
        for (shared in listOf(false, true)) {
            val exchange = IncumbentExchange.minimizing<Sample>().apply { offer(seed, 10.0) }
            val alns = Alns(
                inner = NoFeasibleLs(problem),
                backtrack = BacktrackSolver(problem),
                backtrackParams = BacktrackParams(randomSeed = 3),
                pooledIncumbents = exchange.takeIf { shared },
                repairOperators = listOf(BacktrackRepair(maxDecisions = 8)),
                minDestroyFraction = 1.0,
                maxDestroyFraction = 1.0,
                maxIterations = 1,
                flipsPerIteration = 8,
                rng = Random(3),
            )
            val incumbents = mutableListOf<Double>()
            val handle = assertIs<InstructionSlicedSearch>(
                alns.resumable(
                    objective,
                    LocalSearchParams(
                        initialAssignment = seed.takeUnless { shared },
                        maxInstructions = 8,
                    ),
                ),
            )

            val result = handle.use {
                assertNotNull(
                    it.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 8) { found ->
                        incumbents += objective.evaluate(found.sample)
                    },
                )
            }

            val sample = assertNotNull(result.assignment)
            assertEquals(listOf(10.0, 1.0), incumbents)
            assertEquals(BigFraction.ONE, objective.evaluateExact(sample))
            assertIs<Verification.Accepted<*, *>>(
                finiteWitnessVerifier(problem, objective).verify(Candidate(sample, 1.0)),
            )
            assertEquals(0L, result.stats.alns.bootstrapCpNodes)
            assertEquals(8L, result.stats.alns.outerAllowance)
        }
    }

    @Test
    fun `an incumbent arriving between slices should release both bootstrap handles before repair`() {
        val problem = selectProblem()
        val exchange = IncumbentExchange.minimizing<Sample>()
        var cpSlices = 0
        var lsSlices = 0
        var cpCloses = 0
        var lsCloses = 0
        var repairs = 0
        val cp = object : ResumableSearch {
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats(search = SearchStats(nodes = SumResult(13.0 + cpCloses)))
            override fun runSlice(
                global: ExecutionControl,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                cpSlices++
                return null
            }
            override fun close() {
                cpCloses++
            }
        }
        val ls = object : ResumableSearch by cp {
            override val stats: SolveStats get() = SolveStats(ls = LocalSearchStats(moves = SumResult(7.0 + lsCloses)))
            override fun runSlice(
                global: ExecutionControl,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                lsSlices++
                return null
            }
            override fun close() {
                lsCloses++
            }
        }
        val backtrack = object :
            ResumableOptimizer<BacktrackParams>, Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun resumable(objective: LinearObjective, params: BacktrackParams) = cp
        }
        val inner = object :
            ResumableOptimizer<LocalSearchParams>, Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun resumable(objective: LinearObjective, params: LocalSearchParams) = ls
        }
        val alns = Alns(
            inner = inner,
            backtrack = backtrack,
            pooledIncumbents = exchange,
            repairOperators = listOf(
                RepairOperator {
                    assertEquals(1, cpCloses)
                    assertEquals(1, lsCloses)
                    repairs++
                    it.incumbent
                },
            ),
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 1,
            flipsPerIteration = 50,
        )
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))
        assertNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
        exchange.offer(Sample(BooleanArray(0), LongArray(0)), 1000.0)
        assertNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
        val seed = selectOptimum()
        exchange.offer(seed, selectObjective.evaluate(seed))
        assertNull(handle.runInstructionSlice(ExecutionControl { true }, Long.MAX_VALUE, 50) {})
        assertEquals(0, cpCloses)
        val result = assertNotNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
        handle.close()
        handle.close()

        assertEquals(seed, result.assignment)
        assertEquals(2, cpSlices)
        assertEquals(2, lsSlices)
        assertEquals(1, cpCloses)
        assertEquals(1, lsCloses)
        assertEquals(1, repairs)
        assertEquals(14L, result.stats.alns.bootstrapCpNodes)
        assertEquals(8L, result.stats.alns.bootstrapLsMoves)
        assertEquals(150L, handle.chargedInstructions)
        assertEquals(50L, result.stats.alns.outerAllowance)
    }

    @Test
    fun `a verified seed with rounded telemetry should keep its original objective`() {
        val problem = selectProblem()
        val seed = selectOptimum()
        val value = selectObjective.evaluate(seed)
        val rounded = Double.fromBits(value.toBits() + 1)
        assertIs<Verification.Accepted<*, *>>(
            finiteWitnessVerifier(problem, selectObjective).verify(Candidate(seed, rounded)),
        )
        val exchange = IncumbentExchange.minimizing<Sample>().apply { offer(seed, rounded) }

        val result = assertIs<MinimizeResult.BestFound>(
            Alns(NoFeasibleLs(problem), maxIterations = 0, pooledIncumbents = exchange)
                .minimize(selectObjective, LocalSearchParams()),
        )

        assertEquals(seed, result.sample)
        assertEquals(value, result.objective)
        assertEquals(1L, assertNotNull(exchange.current()).version)
    }

    @Test
    fun `compatible pinned shared incumbents should be able to seed repair`() {
        val seed = selectOptimum()
        val params = LocalSearchParams(assumptions = Assumptions(bools = mapOf(0 to seed.boolValue(0))))
        val result = Alns(
            NoFeasibleLs(selectProblem()),
            maxIterations = 0,
            pooledIncumbents = selectExchangeHolding(seed),
        ).minimize(selectObjective, params)

        assertEquals(seed, result.assignment)
    }

    @Test
    fun `a scoped inner session should validate its seed through bootstrap`() {
        val problem = selectProblem()
        val inner = LocalSearchSolver(problem)
        val seed = selectOptimum()
        inner.session().use { scoped ->
            scoped.push(Assumptions(bools = SELECT_DEAREST.associateWith { true }))
            val alns = Alns(inner, session = scoped, maxIterations = 0)

            val result = alns.minimize(
                selectObjective,
                LocalSearchParams(
                    initialAssignment = seed,
                    maxFlips = 1,
                    randomSeed = 3,
                ),
            )

            val sample = assertNotNull(result.assignment)
            assertTrue(SELECT_DEAREST.all { sample.boolValue(it) })
        }
    }

    @Test
    fun `deduced restrictions should admit only compatible supplied incumbents without exact pins`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyList<Factor>()).bake()
        val restrictions = listOf(
            Assumptions.None.withTightenedMin(0, 1) to 0L,
            Assumptions.None.withTightenedMax(0, 1) to 2L,
            Assumptions.None.withIntHole(0, 0) to 0L,
            Assumptions(
                IntArray(0),
                BooleanArray(0),
                IntArray(0),
                LongArray(0),
                DeducedRestrictions(
                    intSetKeys = intArrayOf(0),
                    intSetOffsets = intArrayOf(0, 1),
                    intSetValues = longArrayOf(1),
                ),
            ) to 0L,
        )
        for ((assumptions, excluded) in restrictions) {
            for (value in listOf(excluded, 1L)) {
                val seed = Sample(BooleanArray(0), longArrayOf(value))
                val result = Alns(NoFeasibleLs(problem), maxIterations = 0).minimize(
                    LinearObjective(intCoefficients = longArrayOf(1)),
                    LocalSearchParams(initialAssignment = seed, assumptions = assumptions),
                )

                if (value == excluded) {
                    assertIs<MinimizeResult.Unknown>(result)
                } else {
                    assertEquals(seed, result.assignment)
                }
            }
        }
    }

    @Test
    fun `a worse shared incumbent should not replace a supplied seed`() {
        val seed = selectOptimum()
        val worse = Sample(BooleanArray(SELECT_WEIGHTS.size) { true }, LongArray(0))
        val result = Alns(
            NoFeasibleLs(selectProblem()),
            maxIterations = 0,
            pooledIncumbents = selectExchangeHolding(worse),
        ).minimize(
            selectObjective,
            LocalSearchParams(initialAssignment = seed),
        )

        assertEquals(seed, result.assignment)
    }

    @Test
    fun `seed ordering should preserve exact improvements hidden by rounded telemetry`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyList<Factor>()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1), constant = 9_007_199_254_740_992)
        val supplied = Sample(BooleanArray(0), longArrayOf(1))
        val improved = Sample(BooleanArray(0), longArrayOf(0))
        val exchange = IncumbentExchange.minimizing<Sample>().apply { offer(improved, objective.evaluate(improved)) }

        val result = Alns(NoFeasibleLs(problem), maxIterations = 0, pooledIncumbents = exchange).minimize(
            objective,
            LocalSearchParams(initialAssignment = supplied),
        )

        assertEquals(objective.evaluate(supplied), objective.evaluate(improved))
        assertEquals(improved, result.assignment)
    }

    @Test
    fun `real seeds should require the configured source guarantee and full finite coordinates`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyList<Factor>(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val approximate = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        val exact = approximate.copy(
            reals = doubleArrayOf(0.5),
            exactReals = listOf(BigFraction.ofLong(2).reciprocal()),
        )
        for (tolerance in listOf(false, true)) {
            for (seed in listOf(
                approximate,
                exact,
                Sample(BooleanArray(0), LongArray(0)),
                Sample(BooleanArray(0), LongArray(0), doubleArrayOf(Double.NaN)),
            )) {
                val alns = Alns(NoFeasibleLs(problem), maxIterations = 0).apply {
                    incumbentToleranceCheck = if (tolerance) {
                        { sample -> sample.numRealVars == 1 && sample.approximateRealValue(0) == 0.5 }
                    } else {
                        null
                    }
                }
                val result = alns.minimize(objective, LocalSearchParams(initialAssignment = seed))

                if (seed === exact || (tolerance && seed === approximate)) {
                    assertEquals(seed, result.assignment)
                } else {
                    assertIs<MinimizeResult.Unknown>(result)
                }
            }
        }
    }

    @Test
    fun `a checked supplied seed should survive invocation expiry before shared polling`() {
        val problem = Problem(
            0,
            0,
            emptyArray(),
            emptyList<Factor>(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val seed = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))
        var checked = false
        var polls = 0
        val inner = object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams) = error("bootstrap")
        }
        val alns = Alns(inner, maxIterations = 0).apply {
            incumbentToleranceCheck = {
                checked = true
                it.approximateRealValue(0) == 0.5
            }
        }
        val handle = alns.resumable(objective, LocalSearchParams(initialAssignment = seed))

        val first = handle.runSlice(ExecutionControl { checked && ++polls >= 3 }, Long.MAX_VALUE) {}
        val result = handle.use { first ?: assertNotNull(it.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {}) }

        assertEquals(seed, result.assignment)
    }

    @Test
    fun `adoption close failures should release all children and solver ownership exactly once`() {
        val problem = selectProblem()
        val exchange = IncumbentExchange.minimizing<Sample>()
        var cpCloses = 0
        var lsCloses = 0
        val cp = object : ResumableSearch {
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats.EMPTY
            override fun runSlice(
                global: ExecutionControl,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? = null
            override fun close() {
                cpCloses++
                error("CP close")
            }
        }
        val ls = object : ResumableSearch by cp {
            override fun close() {
                lsCloses++
            }
        }
        val backtrack = object :
            ResumableOptimizer<BacktrackParams>, Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun resumable(objective: LinearObjective, params: BacktrackParams) = cp
        }
        val inner = object :
            ResumableOptimizer<LocalSearchParams>, Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun resumable(objective: LinearObjective, params: LocalSearchParams) = ls
        }
        val alns = Alns(inner, backtrack = backtrack, pooledIncumbents = exchange, maxIterations = 0)
        val handle = alns.resumable(selectObjective, LocalSearchParams())
        assertNull(handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {})
        val seed = selectOptimum()
        exchange.offer(seed, selectObjective.evaluate(seed))

        val failure = assertFailsWith<IllegalStateException> {
            handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {}
        }
        handle.close()
        alns.resumable(selectObjective, LocalSearchParams()).close()

        assertEquals("CP close", failure.message)
        assertEquals(1, cpCloses)
        assertEquals(1, lsCloses)
    }

    @Test
    fun `cancelled literal scope expansion should consume its charged iteration once`() {
        val problem = Problem(301, 0, emptyArray(), emptyArray()).bake()
        val sweep = assertNotNull(
            DefinitionalSweep.infer(
                problem,
                boolFolds = (0 until 300).map {
                    BoolFoldDefinition(it + 1, intArrayOf(Lit.make(it, true)), true)
                },
            ),
        )
        val initial = Sample(BooleanArray(301), LongArray(0))
        val objective = LinearObjective(boolWeights = LongArray(301) { if (it == 0) 1 else 0 })
        var expanding = false
        var polls = 0
        var destroys = 0
        var repairs = 0
        val alns = Alns(
            inner = bootstrapAt(problem, initial),
            destroyOperators = listOf(
                DestroyOperator { _, _, _, _, _ ->
                    destroys++
                    expanding = true
                    FreedVars(intArrayOf(0), intArrayOf())
                },
            ),
            repairOperators = listOf(
                RepairOperator { context ->
                    repairs++
                    context.incumbent
                },
            ),
            maxIterations = 1,
            flipsPerIteration = 8,
            literalSweep = sweep,
        )

        val observed = ArrayList<AlnsIterationObservation>()
        alns.iterationObserver = AlnsIterationObserver { observed += it }.owner("alns/test", 7L, 301, 0, 0)

        alns.resumable(objective, LocalSearchParams()).use { handle ->
            val first = handle.runSlice(ExecutionControl { expanding && ++polls >= 2 }, Long.MAX_VALUE) {}
            val result = assertNotNull(first ?: handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {})

            assertEquals(8L, result.stats.alns.outerAllowance)
            assertEquals(1, destroys)
            assertEquals(0, repairs)
        }
        val observation = observed.single()
        assertEquals("Censored", observation.endpoint)
        assertFalse(observation.scopeComplete)
        assertNull(observation.reward)
        assertEquals(8L, observation.allowance)
    }

    @Test
    fun `unit literal neighborhoods should release source and nested output aliases together`() {
        val problem = chainProblem()
        val sweep = chainSweep(problem)
        val initial = Sample(booleanArrayOf(false, true, false), LongArray(0))
        val objective = LinearObjective(boolWeights = longArrayOf(0, 3, 0))
        for (destroyed in 0..2) {
            val alns = Alns(
                inner = bootstrapAt(problem, initial),
                destroyOperators = listOf(
                    DestroyOperator { _, _, _, _, _ ->
                        FreedVars(intArrayOf(destroyed), intArrayOf())
                    },
                ),
                repairOperators = listOf(
                    RepairOperator { context ->
                        BacktrackRepair(maxDecisions = 8).repair(
                            context.copy(
                                backtrack = BacktrackSolver(problem),
                                backtrackParams = BacktrackParams(randomSeed = 3L),
                            ),
                        )
                    },
                ),
                maxIterations = 1,
                literalSweep = sweep,
            )

            val result = alns.minimize(objective, LocalSearchParams(randomSeed = 3L))

            assertEquals(0.0, objective.evaluate(assertNotNull(result.assignment)), "destroyed=$destroyed")
        }
    }

    @Test
    fun `unit literal neighborhoods should preserve caller pins`() {
        val problem = chainProblem()
        val sweep = chainSweep(problem)
        val initial = Sample(booleanArrayOf(false, true, false), LongArray(0))
        val objective = LinearObjective(boolWeights = longArrayOf(0, 3, 0))
        for (pinned in 0..2) {
            var repairs = 0
            val alns = Alns(
                inner = bootstrapAt(problem, initial),
                destroyOperators = listOf(
                    DestroyOperator { _, _, _, _, _ ->
                        FreedVars(intArrayOf((pinned + 1) % 3), intArrayOf())
                    },
                ),
                repairOperators = listOf(
                    RepairOperator { context ->
                        repairs++
                        assertFalse(pinned in context.freed.bools)
                        assertEquals(initial.boolValue(pinned), context.pinAssumptions.boolValueOrNull(pinned))
                        BacktrackRepair(maxDecisions = 8).repair(
                            context.copy(
                                backtrack = BacktrackSolver(problem),
                                backtrackParams = BacktrackParams(randomSeed = 3L),
                            ),
                        )
                    },
                ),
                maxIterations = 1,
                literalSweep = sweep,
            )
            val params = LocalSearchParams(
                randomSeed = 3L,
                assumptions = Assumptions(bools = mapOf(pinned to initial.boolValue(pinned))),
            )

            val result = alns.minimize(objective, params)

            assertEquals(3.0, objective.evaluate(assertNotNull(result.assignment)), "pinned=$pinned")
            assertEquals(1, repairs)
        }
    }

    @Test
    fun `alns should offer its accepted incumbents to the shared exchange`() {
        val exchange = IncumbentExchange.minimizing<Sample>()

        val sample = selectAlns(exchange).minimize(selectObjective, LocalSearchParams(maxFlips = 200L, randomSeed = 1L))
            .assignment

        assertNotNull(sample)
        val standing = assertNotNull(exchange.current(), "at least the initial incumbent must be installed")
        assertEquals(selectObjective.evaluate(sample), standing.objective)
    }

    @Test
    fun `alns should adopt a better published solution before destroying`() {
        val optimal = selectOptimum()
        val exchange = selectExchangeHolding(optimal)
        val sample = selectAlns(exchange).minimize(selectObjective, starvedBootstrap()).assignment
        assertSame(optimal, sample, "the published optimum, not a locally-found equal, is the reported best")
    }

    @Test
    fun `alns should not import a published solution under assumption pins`() {
        val optimal = selectOptimum()
        val exchange = selectExchangeHolding(optimal)
        val params = starvedBootstrap().copy(assumptions = Assumptions(bools = SELECT_DEAREST.associateWith { true }))
        val sample = selectAlns(exchange).minimize(selectObjective, params).assignment
        assertNotNull(sample)
        assertNotSame(optimal, sample, "a foreign full assignment may violate the pins, so none is imported")
    }

    @Test
    fun `greedy construction should respect pinned vars`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val inner = LocalSearchSolver(problem.bake())
        val pinAssumptions = Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false))
        val context = RepairContext(
            inner = inner,
            params = LocalSearchParams(randomSeed = 0L),
            objective = objective,
            pinAssumptions = pinAssumptions,
            incumbent = Sample(booleanArrayOf(false, false, false, false), LongArray(0)),
            freed = FreedVars(intArrayOf(3), IntArray(0)),
            rng = Random(0),
        )
        val sample = GreedyConstructionRepair().repair(context)
        assertNotNull(sample)
        assertEquals(false, sample.bools[0])
        assertEquals(false, sample.bools[1])
        assertEquals(false, sample.bools[2])
        assertEquals(true, sample.bools[3])
    }

    @Test
    fun `backtrack repair should stop at the run cancellation on both the reused and the fresh solve path`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor)).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val solver = BacktrackSolver(problem)
        for (reused in listOf(true, false)) {
            solver.openRepair(objective, BacktrackParams()).use { handle ->
                val context = RepairContext(
                    inner = LocalSearchSolver(problem),
                    params = LocalSearchParams(randomSeed = 0L, cancellation = ExecutionControl { true }),
                    objective = objective,
                    pinAssumptions = Assumptions.None,
                    incumbent = Sample(booleanArrayOf(true, false, false, false), LongArray(0)),
                    freed = FreedVars(intArrayOf(0, 1, 2, 3), IntArray(0)),
                    backtrack = solver,
                    backtrackParams = BacktrackParams(),
                    repairSearch = handle.takeIf { reused },
                    bestObjective = 10.0,
                )
                assertNull(BacktrackRepair().repair(context), "reused=$reused")
            }
        }
    }

    @Test
    fun `an acceptance factory should override the fixed acceptance and see the initial objective`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        var seenInitial = Double.NaN
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 8,
            // The fixed acceptance would allow worsening incumbents; the factory's Improving must win.
            acceptance = AcceptanceCriterion.RandomWalk,
            acceptanceFor = { initial ->
                seenInitial = initial
                AcceptanceCriterion.Improving
            },
        )
        alns.minimize(objective, LocalSearchParams(maxFlips = 200L, randomSeed = 1L))
        assertTrue(seenInitial.isFinite(), "the factory receives the initial incumbent's objective")
        val incumbents = alns.iterationLog.map { it.incumbentObjective }
        assertTrue(
            incumbents.zipWithNext().all { (a, b) -> b <= a },
            "the factory's Improving policy must govern, so the incumbent never worsens: $incumbents",
        )
    }

    @Test
    fun `a cancelled backtrack bootstrap should not start a local search fallback`() {
        val problem = selectProblem()
        var expired = false
        val backtrack = object : Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult {
                expired = true
                return MinimizeResult.Unknown(TerminationReason.Cancelled)
            }
        }
        val alns = Alns(inner = NoFeasibleLs(problem), backtrack = backtrack)

        val result = alns.minimize(selectObjective, LocalSearchParams(cancellation = ExecutionControl { expired }))

        assertEquals(TerminationReason.Cancelled, (result as MinimizeResult.Unknown).reason)
    }
}
