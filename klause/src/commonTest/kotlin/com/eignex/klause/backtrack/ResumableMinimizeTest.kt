package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.SmallestDomain
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.FloatLpResult
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpSolveCursor
import com.eignex.klause.lp.engine.LpSolveMetrics
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.lp.engine.ResumableLpSolver
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsoundnessException
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.PreparingSearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class UnresolvedRealLeafFixture(val withIncumbent: Boolean) {
    val problem = Problem(
        numBoolVars = 0,
        numIntVars = if (withIncumbent) 1 else 0,
        intDomains = if (withIncumbent) arrayOf(IntDomain(0L, 1L)) else emptyArray(),
        factors = arrayOf(
            Linear(
                if (withIncumbent) longArrayOf(1L) else longArrayOf(),
                if (withIncumbent) intArrayOf(0) else intArrayOf(),
                doubleArrayOf(2.0),
                intArrayOf(0),
                LinearOp.EQ,
                if (withIncumbent) 2L else 1L,
            ),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(1.0),
    ).bake()
    val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
    var opened = 0
    var closed = 0
    var persistentOpened = 0
    var persistentClosed = 0
    var visited = 0
    val factory = RecordingLpEngineFactory(object : LpEngineFactory by ProductionLpEngineFactory {
        override fun newPersistentSolver(
            model: LpModel,
            cancellation: Cancellation,
            refactorUpdateLimit: Int,
            iterationLimit: Int,
            workLimit: Long,
            trackDegeneracy: Boolean,
            pricing: LpPricingOptions,
        ): PersistentLpSolver {
            val delegate = ProductionLpEngineFactory.newPersistentSolver(
                model,
                cancellation,
                refactorUpdateLimit,
                iterationLimit,
                workLimit,
                trackDegeneracy,
                pricing,
            )
            val leaf = iterationLimit == 0 && workLimit == 0L
            persistentOpened++
            if (leaf) opened++
            return object : PersistentLpSolver by delegate {
                override fun solve(warm: Basis?): FloatLpResult? {
                    if (leaf) visited++
                    return delegate.solve(warm)
                }

                override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                    if (leaf) visited++
                    return delegate.resolveBounds(allowance)
                }

                override fun close() {
                    persistentClosed++
                    if (leaf) closed++
                    delegate.close()
                }
            }
        }
    })
    var acceptProof: (Int, LpCertifier) -> Boolean = { leaf, _ -> withIncumbent && leaf == 1 }
    val attempts = ArrayList<Pair<Int, Boolean>>()
    val context = LpSolveContext(
        factory,
        object : LpCertificationPolicy {
            override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
                val leaf = visited
                attempts += leaf to successful
                return successful && acceptProof(leaf, certifier)
            }
        },
    )
    val solver = BacktrackSolver(problem, context)
    val params = BacktrackParams(
        randomSeed = 0L,
        valueSelector = IndomainMin,
        lpPlan = LpPlan(componentSplit = false),
    )

    fun assertVisitedLeaves(ownersReleased: Boolean = true) {
        assertEquals(if (withIncumbent) 2 else 1, visited)
        assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
        assertTrue(attempts.any { it.first == (if (withIncumbent) 2 else 1) && it.second })
        assertEquals(if (ownersReleased) opened else 0, closed)
    }

    fun assertIncumbent(sample: Sample) {
        assertEquals(0L, sample.ints.single())
        assertEquals(1.0, sample.reals.single())
        assertEquals(2.0, sample.ints.single() + 2.0 * sample.reals.single())
        assertTrue(sample.reals.single() in 0.0..1.0)
        assertTrue(0.5 < objective.evaluate(sample))
    }
}

internal object FiniteCutoffKnapsackFixture {
    val weights = intArrayOf(6, 3)
    val profits = longArrayOf(7, 8)
    val problem = Problem(
        0,
        weights.size,
        Array(weights.size) { IntDomain(0, 1) },
        arrayOf(Linear(weights.copyOf(), IntArray(weights.size) { it }, LinearOp.LE, 25)),
    ).bake()
    val objective = LinearObjective(intCoefficients = LongArray(profits.size) { -profits[it] }, constant = 11L)
    val params = BacktrackParams(
        randomSeed = 62L,
        lubyRestartBase = 1,
        lpPlan = LpPlan(bounding = true, boundEvery = 1, rootMaxWork = 1L),
    )

    fun sourceCost(mask: Int): Long = 11L - profits.indices.sumOf { if (mask and (1 shl it) != 0) profits[it] else 0L }

    fun improvingMasks(cutoff: Long, firstValue: Long? = null): List<Int> =
        (0 until (1 shl weights.size)).filter { mask ->
            (firstValue == null || (mask and 1).toLong() == firstValue) &&
                weights.indices.sumOf { if (mask and (1 shl it) != 0) weights[it] else 0 } <= 25 &&
                sourceCost(mask) < cutoff
        }
}

class ResumableMinimizeTest {
    @Test
    fun `paused root queries finish before the worker starts discrete traversal`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
            settings = ProblemSettings(lpCeilingTableauCells = 1L)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
        var constructions = 0
        var queries = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int, iterationLimit: Int,
                workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                constructions++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(model, cancellation,
                    refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing)
                return object : PersistentLpSolver by delegate, ResumableLpSolver {
                    private var pausedWork = 0L
                    override val currentWork: Long get() = pausedWork + delegate.lastMetrics.workOps
                    override val lastMetrics: LpSolveMetrics get() =
                        delegate.lastMetrics.copy(workOps = currentWork)
                    override fun openSolve(
                        allowance: LpFloatAllowance?, retainFundingStop: Boolean,
                    ): LpSolveCursor<FloatLpResult?> = LpSolveCursor {
                        queries++
                        repeat(2) {
                            pausedWork += 5_000L * LP_WORK_PER_NODE
                            pauseAt(pausedWork)
                        }
                        delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        val params = BacktrackParams(maxDecisions = 0L,
            lpConfig = LpConfig.parse("default,+sparse-trial"),
            lpPlan = LpPlan(probe = true, rootCutHarvest = false))
        val offered = ArrayList<Sample>()

        BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, 100_000L) { offered += it.sample }
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, 100_000L) { offered += it.sample }
        }

        assertEquals(listOf(1L, 0L), offered.single().ints.toList())
        assertEquals(1, queries)
        assertEquals(1, constructions)
    }

    @Test
    fun `sliced root rounding should resume only while its LP allowance remains`() {
        val charged = 5_000L * LP_WORK_PER_NODE
        for (allowance in listOf(charged, 2L * charged)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            )).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1, 2))
            var primalSolves = 0
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
                        model, cancellation, iterationLimit, workLimit, trackDegeneracy, pricing,
                    )
                    if (workLimit == 0L) return object : TableauCutSolver by delegate {
                        override fun solve(warm: Basis?): FloatLpResult? = null
                    }
                    val first = primalSolves++ == 0
                    return object : TableauCutSolver by delegate {
                        override val lastTermination get() =
                            if (first) LpFloatTermination.CANCELLED else delegate.lastTermination
                        override val lastMetrics get() =
                            if (first) LpSolveMetrics(workOps = charged) else delegate.lastMetrics
                        override fun solve(warm: Basis?): FloatLpResult? {
                            if (!first) return if (warm == null) null else delegate.solve(warm)
                            val result = delegate.solve(warm) ?: return null
                            return FloatLpResult(result.basis, result.objective, result.duals, result.primal,
                                optimal = false)
                        }
                    }
                }
            }
            val params = BacktrackParams(
                maxDecisions = 0L,
                lpPlan = LpPlan(bounding = true, probe = true, rootCutHarvest = false,
                    boundAdaptiveWork = false, rootMaxWork = allowance),
            )
            val offered = ArrayList<Sample>()

            BacktrackSolver(problem, LpSolveContext(factory)).resumable(objective, params).use { search ->
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, 5_000L) { offered += it.sample }
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, 100_000L) { offered += it.sample }
            }

            if (allowance > charged) {
                assertEquals(listOf(1L, 0L), offered.single().ints.toList())
                assertEquals(2, primalSolves)
            } else {
                assertTrue(offered.isEmpty())
                assertEquals(1, primalSolves)
            }
        }
    }

    @Test
    fun `closing an abandoned improvement stream should release additional peers`() {
        val closed = ArrayList<Int>()
        val solver = BacktrackSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())
        val stream = solver.openImprovements(
            LinearObjective(boolWeights = longArrayOf(-1, -1)),
            BacktrackParams(componentFactory = {
                List(2) { index ->
                    object : com.eignex.klause.solver.search.SearchComponent, AutoCloseable {
                        override fun close() {
                            closed += index
                        }
                    }
                }
            }),
        )
        assertTrue(stream.hasNext())
        stream.next()

        stream.close()
        stream.close()

        assertEquals(listOf(1, 0), closed)
    }

    @Test
    fun `a Boolean objective should retain component preparation before publishing`() {
        var batches = 0
        val component = object : PreparingSearchComponent {
            override fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?> = sequence {
                repeat(3) {
                    batches++
                    yield(null)
                }
                yield(ComponentResult.Consistent)
            }.iterator()
        }
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1))
        val search = BacktrackSolver(problem).resumable(
            objective,
            BacktrackParams(componentFactory = { listOf(component) }),
        )
        assertEquals(0, batches)
        assertTrue(search.preparationPending)

        var result: MinimizeResult? = null
        search.use {
            while (result == null) {
                result = it.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L) {
                    assertEquals(3, batches)
                }
            }
        }

        assertEquals(0.0, assertIs<MinimizeResult.Optimal>(result).objective)
        assertEquals(3, batches)
    }

    @Test
    fun `optimization should deliver successful deductions to its finite selector`() {
        val deductions = ArrayList<PropagationResult.Implied>()
        val base = com.eignex.klause.backtrack.selector.InputOrder
        val selector = object : VariableSelector by base {
            override fun fresh(): VariableSelector = this
            override fun onPropagation(implied: PropagationResult.Implied) {
                deductions += implied
            }
        }
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )

        val result = BacktrackSolver(problem.bake()).minimize(
            LinearObjective(boolWeights = longArrayOf(-1, 0)),
            BacktrackParams(variableSelector = selector, valueSelector = IndomainMax),
        )

        assertIs<MinimizeResult.Optimal>(result)
        assertTrue(deductions.any { it.boolValueOrNull(1) == true })
        assertTrue(result.stats.search.propagations.sum > 0.0)
    }

    @Test
    fun `a completed clausal proposal should be delivered while work debt blocks search`() {
        val solver = BacktrackSolver(
            Problem(
                2,
                0,
                emptyArray(),
                Array(2_000) { Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))) },
            ).bake(),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1L))
        val samples = mutableListOf<MinimizeResult.WithSample>()

        ResumableMinimize(solver, objective, BacktrackParams()).use { search ->
            repeat(100) {
                if (search.stats.search.clausalPrimalProposals.sum == 0.0) {
                    search.runSlice(Cancellation.Never, 60_000L, 1L) { samples.add(it) }
                }
            }
            search.runSlice(Cancellation.Never, 60_000L, 0L) { samples.add(it) }

            assertEquals(0L, objective.evaluateLong(samples.single().sample))
            assertTrue(samples.single().sample.bools[1])
        }
    }

    @Test
    fun `rebind should prove the same optimum after an exhausted fragment`() {
        val objective = LinearObjective(boolWeights = longArrayOf(-1L))
        val solver = BacktrackSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())

        ResumableMinimize(solver, objective, BacktrackParams(), pausable = false, rebindable = true).use { search ->
            repeat(3) {
                if (it > 0) search.rebind(Assumptions.None, 100L)
                val result = assertIs<MinimizeResult.Optimal>(
                    search.runSlice(Cancellation.Never, 1000L, 100L) {},
                )
                assertEquals(-1.0, result.objective)
            }
        }
    }

    @Test
    fun `rebind should preserve a restricted optimum after LP bounding`() {
        val weights = intArrayOf(6, 7, 2, 3, 5, 9, 4, 3, 4)
        val profits = longArrayOf(7, 5, 4, 3, 8, 6, 5, 8, 6)
        val problem = Problem(
            0,
            weights.size,
            Array(weights.size) { IntDomain(0, 1) },
            arrayOf(Linear(weights.copyOf(), IntArray(weights.size) { it }, LinearOp.LE, 25)),
        ).bake()
        val objective = LinearObjective(intCoefficients = LongArray(profits.size) { -profits[it] })
        val params = BacktrackParams(
            randomSeed = 62L,
            lubyRestartBase = 1,
            lpPlan = LpPlan(bounding = true, boundEvery = 1),
        )

        ResumableMinimize(BacktrackSolver(problem), objective, params, rebindable = true).use { search ->
            val initial = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-38.0, initial.objective)

            search.rebind(Assumptions.None.withInt(0, 0), 100000L)
            val restricted = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-36.0, restricted.objective)
            assertEquals(0L, restricted.sample.ints[0])
            assertTrue(weights.indices.sumOf { weights[it] * restricted.sample.ints[it] } <= 25L)
            assertEquals(-36L, objective.evaluateLong(restricted.sample))
        }
    }

    @Test
    fun `retained search should revalidate a pooled witness and tighten its first root`() {
        var firstMaximum: Long? = null
        val selector = object : VariableSelector {
            override fun pick(session: PropagationSession, rng: Random): VarRef? {
                if (firstMaximum == null) firstMaximum = session.intDomain(0).max
                return SmallestDomain.pick(session, rng)
            }

            override fun fresh(): VariableSelector = this
        }
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(
            variableSelector = selector,
            valueSelector = IndomainMin,
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 5.0 },
            pooledIncumbents = IncumbentExchange.minimizing<Sample>().apply {
                offer(Sample(BooleanArray(0), longArrayOf(5L)), 5.0)
            },
        )

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {},
            )

            assertEquals(4L, firstMaximum)
            assertEquals(0.0, result.objective)
            assertEquals(TerminationReason.SearchExhausted, result.reason)
        }
    }

    @Test
    fun `a pooled proof bound should preserve the first discovered improvement`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(
            valueSelector = SolutionGuided(IndomainMin),
            solutionPhasing = true,
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 5.0 },
            pooledIncumbents = IncumbentExchange.minimizing<Sample>().apply {
                offer(Sample(BooleanArray(0), longArrayOf(5L)), 5.0)
            },
        )
        val offers = mutableListOf<Double>()

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { offers += it.objective }
        }

        assertEquals(listOf(5.0, 0.0), offers)
    }

    @Test
    fun `restarting retained search should discover its own first incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(
            valueSelector = IndomainMin,
            lubyRestartBase = 10L,
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 5.0 },
            pooledIncumbents = IncumbentExchange.minimizing<Sample>().apply {
                offer(Sample(BooleanArray(0), longArrayOf(5L)), 5.0)
            },
        )
        val offers = mutableListOf<Double>()

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { offers += it.objective }
        }

        assertEquals(listOf(0.0), offers)
    }

    @Test
    fun `retained search should reject an invalid pooled witness at the shared cutoff`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(
            valueSelector = IndomainMin,
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 5.0 },
            pooledIncumbents = IncumbentExchange.minimizing<Sample>().apply {
                offer(Sample(BooleanArray(0), longArrayOf(5L)), 5.0)
            },
        )
        val offers = mutableListOf<Double>()

        BacktrackSolver(problem).resumable(objective, params).use { search ->
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { offers += it.objective }
        }

        assertEquals(listOf(0.0), offers)
    }

    @Test
    fun `a rebindable search should reject a starting witness equal to its cutoff`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(
            valueSelector = IndomainMin,
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 5.0 },
        )
        val offers = mutableListOf<Double>()

        ResumableMinimize(
            BacktrackSolver(problem), objective, params, rebindable = true,
            initialCandidate = Sample(BooleanArray(0), longArrayOf(5L)),
        ).use { search ->
            search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { offers += it.objective }
        }

        assertEquals(listOf(0.0), offers)
    }

    @Test
    fun `finite cutoff LP fixings should preserve improving source assignments across rebind`() {
        val fixture = FiniteCutoffKnapsackFixture
        var observedSession: PropagationSession? = null
        val params = fixture.params.copy(
            objectiveBoundSupplier = { 4.0 },
            clauseExchange = onSearchStart { observedSession = it },
        )

        ResumableMinimize(
            BacktrackSolver(fixture.problem),
            fixture.objective,
            params,
            pausable = false,
            rebindable = true,
        ).use { search ->
            val initial = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-4.0, initial.objective)
            assertTrue(search.stats.lp.rootReducedCostFixes.sum > 0.0)
            assertEquals(1L, assertNotNull(observedSession).intDomain(1).min)

            search.rebind(Assumptions.None.withInt(0, 0), 100000L)
            val restricted = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(TerminationReason.SearchExhausted, restricted.reason)
            assertEquals(3.0, restricted.objective)
        }
    }

    @Test
    fun `finite cutoff without LP should leave the source domain free`() {
        val fixture = FiniteCutoffKnapsackFixture
        var observedSession: PropagationSession? = null
        val params = fixture.params.copy(
            lpPlan = LpPlan(bounding = false),
            objectiveBoundSupplier = { 4.0 },
            clauseExchange = onSearchStart { observedSession = it },
        )

        ResumableMinimize(
            BacktrackSolver(fixture.problem),
            fixture.objective,
            params,
            pausable = false,
            rebindable = true,
        ).use { search ->
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100000L) {},
            )
            assertEquals(-4.0, result.objective)
            assertEquals(0.0, search.stats.lp.nodePasses.sum)
            assertEquals(0.0, search.stats.lp.fixed.sum)
            val domain = assertNotNull(observedSession).intDomain(1)
            assertEquals(0L, domain.min)
            assertEquals(1L, domain.max)
        }
    }

    @Test
    fun `raw repair should reject an increasing or NaN cutoff`() {
        val fixture = FiniteCutoffKnapsackFixture
        for (invalid in listOf(5.0, Double.NaN)) {
            var cutoff = 4.0
            val params = fixture.params.copy(objectiveBoundSupplier = { cutoff })
            ResumableMinimize(
                BacktrackSolver(fixture.problem),
                fixture.objective,
                params,
                pausable = false,
                rebindable = true,
            ).use { search ->
                assertIs<MinimizeResult.BestFound>(
                    search.runSlice(Cancellation.Never, 1000L, 100000L) {},
                )
                cutoff = invalid
                search.rebind(Assumptions.None.withInt(0, 2), 100000L)
                assertFailsWith<IllegalArgumentException> {
                    search.runSlice(Cancellation.Never, 1000L, 100000L) {}
                }
            }
        }
    }

    @Test
    fun `an optimization leaf violating a factor should fail as unsound`() {
        // A selector that stops before any column is fixed stands in for an engine defect that reads an open
        // node as a solved leaf: the minima it reports put both columns of the AllDifferent at 0.
        val stopsEarly = object : VariableSelector {
            override fun pick(session: PropagationSession, rng: Random): VarRef? = null

            override fun fresh(): VariableSelector = this
        }
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val solver = BacktrackSolver(problem.bake())

        assertFailsWith<UnsoundnessException> {
            solver.minimize(objective, BacktrackParams(variableSelector = stopsEarly))
        }
    }

    @Test
    fun `raw repair should publish a second exact improvement with the same displayed score`() {
        val values = listOf(-9_007_199_254_740_992L, -9_007_199_254_740_993L)
        val problem = Problem(0, 1, arrayOf(IntDomain(values.min(), values.max())), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(valueSelector = IndomainMax, objectiveBoundSupplier = { Double.POSITIVE_INFINITY })

        ResumableMinimize(
            BacktrackSolver(problem),
            objective,
            params,
            pausable = false,
            rebindable = true,
        ).use { search ->
            val offers = ArrayList<Long>()
            val result = assertIs<MinimizeResult.BestFound>(
                search.runSlice(Cancellation.Never, 1000L, 100L) { offers += it.sample.ints.single() },
            )
            assertEquals(TerminationReason.SearchExhausted, result.reason)
            assertEquals(values, offers)
            assertEquals(values.min(), result.sample.ints.single())
        }
    }

    @Test
    fun `rebind should clear an indeterminate verdict after a resolved fragment`() {
        val fixture = UnresolvedRealLeafFixture(false)
        fixture.acceptProof = { _, _ -> false }

        ResumableMinimize(fixture.solver, fixture.objective, fixture.params, rebindable = true).use { search ->
            val first = assertIs<MinimizeResult.Unknown>(
                search.runSlice(Cancellation.Never, 1000L, 100L) {},
            )
            assertEquals(TerminationReason.Unsupported, first.reason)

            fixture.acceptProof = { _, _ -> true }
            search.rebind(Assumptions.None, 100L)
            val second = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 100L) {},
            )
            assertEquals(0.5, second.objective)
        }
        assertEquals(fixture.opened, fixture.closed)
    }

    @Test
    fun `a leaf the LP cannot decide should be passed over instead of ending the search`() {
        val n = 3
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                Linear(LongArray(n) { 1L }, IntArray(n) { it }, doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        ).bake()
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val objective = LinearObjective(
            intCoefficients = longArrayOf(2L, 3L, 1L),
            realCoefficients = doubleArrayOf(1.5),
        )
        val search = BacktrackSolver(problem, vetoed).resumable(objective, BacktrackParams(randomSeed = 0L))

        var terminal: MinimizeResult? = null
        while (terminal == null) {
            terminal = search.runSlice(Cancellation.Never, sliceMillis = 60_000, sliceNodes = -1L) { }
        }

        assertTrue(terminal is MinimizeResult.Unknown && search.stats.lp.standalonePasses.sum > 1.0, "$terminal")
    }

    @Test
    fun `cancelled optimization preparation should resume without searching partial state`() {
        val fixture = FiniteCutoffKnapsackFixture
        val factory = RecordingLpEngineFactory()
        var cancelled = false
        val params = fixture.params.copy(
            cancellation = Cancellation { cancelled },
            clauseExchange = onSearchStart { cancelled = true },
        )
        val solver = BacktrackSolver(fixture.problem, LpSolveContext(factory))

        solver.resumable(fixture.objective, params).use { search ->
            assertNull(search.runSlice(params.cancellation, Long.MAX_VALUE, -1L) {})
            assertEquals(0.0, search.stats.search.nodes.sum)

            cancelled = false
            assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) {})
        }
    }

    @Test
    fun `cancelled one-shot optimization should return an unknown verdict`() {
        val fixture = FiniteCutoffKnapsackFixture

        val result = BacktrackSolver(fixture.problem).minimize(
            fixture.objective,
            fixture.params.copy(cancellation = Cancellation { true }),
        )

        assertIs<MinimizeResult.Unknown>(result)
    }

    @Test
    fun `one-shot root layout cancellation publishes an unknown terminal`() {
        val fixture = FiniteCutoffKnapsackFixture
        var cancelled = false
        val params = fixture.params.copy(
            cancellation = Cancellation { cancelled },
            clauseExchange = onSearchStart { cancelled = true },
            lpPlan = fixture.params.lpPlan.copy(componentOwnership = true),
        )

        val results = BacktrackSolver(fixture.problem).improvements(fixture.objective, params).toList()

        assertIs<MinimizeResult.Unknown>(results.single())
    }

    @Test
    fun `optimization should publish the strip energy floor at the root`() {
        val factors = arrayOf<Factor>(
            Diffn(IntArray(6) { it }, IntArray(6) { 6 + it }, LongArray(6) { 1L }, LongArray(6) { 1L }),
            Linear(longArrayOf(1, -1), intArrayOf(4, 0), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(4, 2), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(5, 1), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(5, 3), LinearOp.GE, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(12, 4), LinearOp.EQ, 1L),
            Linear(longArrayOf(1, -1), intArrayOf(13, 5), LinearOp.EQ, 1L),
            ArrayMinMax(result = 14, xs = intArrayOf(12, 13), max = true),
        )
        val domains = Array(15) { if (it in 6 until 12) IntDomain(1L, 1L) else IntDomain(0L, 100L) }
        val problem = Problem(0, 15, domains, factors)
        val published = ArrayList<Double>()

        val result = BacktrackSolver(problem.bake()).minimize(
            LinearObjective(intCoefficients = LongArray(15) { if (it == 14) 1L else 0L }),
            BacktrackParams(randomSeed = 1L, objectiveLowerBoundSink = { published.add(it) }),
        )

        assertEquals(6.0, published.firstOrNull())
        assertEquals(6.0, result.objectiveValue)
    }
}

internal fun onSearchStart(action: (PropagationSession) -> Unit) = object : ClauseExchange {
    override fun onRestart(session: PropagationSession) = Unit
    override fun onSearchStart(session: PropagationSession) = action(session)
}
