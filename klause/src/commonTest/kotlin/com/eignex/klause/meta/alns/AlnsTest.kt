package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stat.summary.SumResult
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
    fun `complete bootstrap resumes one search until its first incumbent`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        var opened = 0
        var slices = 0
        var closes = 0
        val backtrack = object :
            ResumableOptimizer<BacktrackParams>,
            Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun minimize(objective: LinearObjective, params: BacktrackParams) = error("cold bootstrap")
            override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch {
                opened++
                return object : ResumableSearch {
                    override val isDone: Boolean get() = slices == 2
                    override val stats: SolveStats
                        get() = SolveStats(search = SearchStats(nodes = SumResult(slices * 13.0)))
                    override fun runSlice(
                        global: Cancellation,
                        sliceMillis: Long,
                        sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit,
                    ): MinimizeResult? {
                        slices++
                        if (slices == 1) return null
                        val result = MinimizeResult.BestFound(
                            sample, objective.evaluate(sample), TerminationReason.BudgetExhausted, stats,
                        )
                        onIncumbent(result)
                        return result
                    }
                    override fun close() { closes++ }
                }
            }
        }
        val alns = Alns(inner = NoFeasibleLs(problem), backtrack = backtrack, maxIterations = 0)
        val callbacks = mutableListOf<Sample>()
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))

        assertNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) { callbacks += it.sample })
        assertEquals(13L, handle.stats.alns.bootstrapCpNodes)
        val result = assertNotNull(
            handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) { callbacks += it.sample },
        )
        handle.close()

        assertSame(sample, result.assignment)
        assertEquals(listOf(sample), callbacks)
        assertEquals(1, opened)
        assertEquals(2, slices)
        assertEquals(1, closes)
        assertEquals(26L, result.stats.alns.bootstrapCpNodes)
        assertEquals(100L, handle.chargedInstructions)
        assertEquals(0L, result.stats.alns.outerAllowance)
        assertTrue(handle.isDone)
    }

    @Test
    fun `local bootstrap retains its walk across instruction slices`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        var opened = 0
        var slices = 0
        var closes = 0
        val inner = object :
            ResumableOptimizer<LocalSearchParams>,
            Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams) = error("cold bootstrap")
            override fun resumable(objective: LinearObjective, params: LocalSearchParams): ResumableSearch {
                opened++
                return object : InstructionSlicedSearch {
                    override val isDone: Boolean get() = slices == 2
                    override val stats: SolveStats
                        get() = SolveStats(ls = LocalSearchStats(moves = SumResult(slices * 7.0)))
                    override fun runSlice(
                        global: Cancellation,
                        sliceMillis: Long,
                        sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit,
                    ): MinimizeResult? = error("instruction slice required")
                    override fun runInstructionSlice(
                        global: Cancellation,
                        sliceMillis: Long,
                        sliceInstructions: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit,
                    ): MinimizeResult? {
                        assertEquals(50L, sliceInstructions)
                        slices++
                        if (slices == 1) return null
                        return MinimizeResult.BestFound(
                            sample, objective.evaluate(sample), TerminationReason.BudgetExhausted, stats,
                        ).also(onIncumbent)
                    }
                    override fun close() { closes++ }
                }
            }
        }
        val alns = Alns(inner = inner, maxIterations = 0)
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))

        assertTrue(handle.preparationPending)
        assertNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) {})
        assertFalse(handle.preparationPending)
        val result = assertNotNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) {})

        assertSame(sample, result.assignment)
        assertEquals(1, opened)
        assertEquals(2, slices)
        assertEquals(1, closes)
        assertEquals(14L, handle.stats.alns.bootstrapLsMoves)
        assertEquals(100L, handle.chargedInstructions)
        assertEquals(0L, result.stats.alns.outerAllowance)
    }

    @Test
    fun `neighborhood slices retain the accepted incumbent and acceptance policy`() {
        val problem = selectProblem()
        val initial = Sample(BooleanArray(SELECT_WEIGHTS.size) { true }, LongArray(0))
        var bootstraps = 0
        var acceptancePolicies = 0
        val incumbents = mutableListOf<Sample>()
        val repairs = mutableListOf<Sample>()
        val inner = object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
                bootstraps++
                return MinimizeResult.BestFound(initial, objective.evaluate(initial), TerminationReason.BudgetExhausted)
            }
        }
        val alns = Alns(
            inner = inner,
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(RepairOperator { context ->
                incumbents += context.incumbent
                Sample(
                    context.incumbent.bools.copyOf().also { it[SELECT_DEAREST[repairs.size]] = false }, LongArray(0),
                ).also(repairs::add)
            }),
            acceptanceFor = { acceptancePolicies++; AcceptanceCriterion.Improving },
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 3,
            flipsPerIteration = 50,
        )
        val callbacks = mutableListOf<Sample>()
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))

        repeat(2) {
            assertNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) { callbacks += it.sample })
        }
        val result = assertNotNull(
            handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) { callbacks += it.sample },
        )
        assertSame(result, handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) {})

        assertEquals(listOf(initial) + repairs.take(2), incumbents)
        assertEquals(listOf(initial) + repairs, callbacks)
        assertSame(repairs.last(), result.assignment)
        assertEquals(1, bootstraps)
        assertEquals(1, acceptancePolicies)
        assertEquals(3, alns.iterationLog.size)
        assertEquals(150L, result.stats.alns.outerAllowance)
        assertEquals(150L, handle.chargedInstructions)
    }

    @Test
    fun `retained CP repairs replace an expired slice token`() {
        val problem = selectProblem()
        var expired = false
        val handles = mutableListOf<RepairSearch>()
        val alns = Alns(
            inner = NoFeasibleLs(problem),
            backtrack = BacktrackSolver(problem),
            backtrackParams = BacktrackParams(randomSeed = 0L),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(RepairOperator { context ->
                val repair = assertNotNull(context.repairSearch)
                handles += repair
                assertNotNull(
                    repair.repair(Assumptions.None, 100, Double.POSITIVE_INFINITY, context.params.cancellation),
                )
                expired = true
                context.incumbent
            }),
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 2,
            flipsPerIteration = 50,
        )
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(LinearObjective(), LocalSearchParams()))

        assertNull(handle.runInstructionSlice(Cancellation { expired }, Long.MAX_VALUE, 50) {})
        assertNotNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) {})

        assertEquals(2, handles.size)
        assertSame(handles[0], handles[1])
    }

    @Test
    fun `CP bootstrap and destroyed neighborhoods respect caller pins`() {
        val problem = selectProblem()
        val pins = Assumptions(bools = SELECT_DEAREST.associateWith { true })
        for (resumable in listOf(false, true)) {
            var repairs = 0
            val alns = Alns(
                inner = NoFeasibleLs(problem),
                backtrack = BacktrackSolver(problem),
                backtrackParams = BacktrackParams(randomSeed = 0L),
                destroyOperators = listOf(DestroyOperator.Random),
                repairOperators = listOf(RepairOperator { context ->
                    repairs++
                    assertTrue(SELECT_DEAREST.all { context.incumbent.bools[it] })
                    assertTrue(SELECT_DEAREST.none { it in context.freed.bools })
                    assertTrue(SELECT_DEAREST.all { context.pinAssumptions.boolValueOrNull(it) == true })
                    context.incumbent
                }),
                minDestroyFraction = 1.0,
                maxDestroyFraction = 1.0,
                maxIterations = 1,
            )
            val params = LocalSearchParams(assumptions = pins)

            val result = if (resumable) {
                alns.resumable(selectObjective, params).use {
                    assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE) {})
                }
            } else {
                alns.minimize(selectObjective, params)
            }

            assertTrue(SELECT_DEAREST.all { assertNotNull(result.assignment).bools[it] })
            assertEquals(1, repairs)
        }
    }

    @Test
    fun `a bootstrap callback failure closes the child and releases the ALNS handle`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        var closes = 0
        val inner = object :
            ResumableOptimizer<LocalSearchParams>,
            Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun resumable(objective: LinearObjective, params: LocalSearchParams) = object : ResumableSearch {
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    onIncumbent(
                        MinimizeResult.BestFound(sample, objective.evaluate(sample), TerminationReason.BudgetExhausted),
                    )
                    return null
                }
                override fun close() { closes++; error("child close") }
            }
        }
        val alns = Alns(inner = inner)
        val handle = alns.resumable(selectObjective, LocalSearchParams())

        val failure = assertFailsWith<IllegalArgumentException> {
            handle.runSlice(Cancellation.Never, Long.MAX_VALUE) { throw IllegalArgumentException("callback") }
        }
        handle.close()
        alns.resumable(selectObjective, LocalSearchParams()).close()

        assertEquals("callback", failure.message)
        assertEquals("child close", failure.suppressedExceptions.single().message)
        assertEquals(1, closes)
    }

    @Test
    fun `closing a paused bootstrap releases both searches when one close fails`() {
        val problem = selectProblem()
        var cpCloses = 0
        var lsCloses = 0
        val cp = object : ResumableSearch {
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats.EMPTY
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? = null
            override fun close() { cpCloses++; error("CP close") }
        }
        val ls = object : ResumableSearch by cp {
            override fun close() { lsCloses++ }
        }
        val backtrack = object :
            ResumableOptimizer<BacktrackParams>,
            Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun resumable(objective: LinearObjective, params: BacktrackParams) = cp
        }
        val inner = object :
            ResumableOptimizer<LocalSearchParams>,
            Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun resumable(objective: LinearObjective, params: LocalSearchParams) = ls
        }
        val alns = Alns(inner = inner, backtrack = backtrack)
        val handle = alns.resumable(selectObjective, LocalSearchParams())

        assertNull(handle.runSlice(Cancellation.Never, Long.MAX_VALUE, 50) {})
        val failure = assertFailsWith<IllegalStateException> { handle.close() }
        handle.close()
        alns.resumable(selectObjective, LocalSearchParams()).close()

        assertEquals("CP close", failure.message)
        assertEquals(1, cpCloses)
        assertEquals(1, lsCloses)
    }

    @Test
    fun `the outer instruction cap spans retained neighborhood slices`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        val allowances = mutableListOf<Long>()
        val inner = object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams) =
                MinimizeResult.BestFound(sample, objective.evaluate(sample), TerminationReason.BudgetExhausted)
        }
        val alns = Alns(
            inner = inner,
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(RepairOperator { context ->
                allowances += context.params.maxFlips
                context.incumbent
            }),
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 8,
            flipsPerIteration = 50,
        )
        val handle = assertIs<InstructionSlicedSearch>(
            alns.resumable(selectObjective, LocalSearchParams(maxInstructions = 120)),
        )

        repeat(2) { assertNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) {}) }
        val result = assertNotNull(handle.runInstructionSlice(Cancellation.Never, Long.MAX_VALUE, 50) {})

        assertEquals(listOf(50L, 50L, 20L), allowances)
        assertSame(sample, result.assignment)
        assertEquals(120L, result.stats.alns.outerAllowance)
        assertEquals(150L, handle.chargedInstructions)
        assertTrue(handle.isDone)
    }

    @Test
    fun `failed bootstrap reports both engines without charging an outer repair`() {
        val problem = selectProblem()
        val backtrack = object : Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun minimize(objective: LinearObjective, params: BacktrackParams) =
                MinimizeResult.Unknown(
                    TerminationReason.BudgetExhausted,
                    SolveStats(search = SearchStats(nodes = SumResult(13.0))),
                )
        }
        val inner = object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams) =
                MinimizeResult.Unknown(
                    TerminationReason.BudgetExhausted,
                    SolveStats(ls = LocalSearchStats(moves = SumResult(7.0))),
                )
        }

        val result = Alns(inner = inner, backtrack = backtrack).minimize(
            selectObjective, LocalSearchParams(maxInstructions = 120),
        )

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(13L, result.stats.alns.bootstrapCpNodes)
        assertEquals(7L, result.stats.alns.bootstrapLsMoves)
        assertEquals(0L, result.stats.alns.outerAllowance)
        assertEquals(0L, result.stats.alns.repairCpNodes)
        assertEquals(0L, result.stats.alns.repairLsMoves)
        assertEquals(7.0, result.stats.ls.moves.sum)
    }

    @Test
    fun `inner repair counters remain distinct from clipped outer allowances`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        var calls = 0
        val inner = object : Optimizer<LocalSearchParams> by LocalSearchSolver(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
                calls++
                return MinimizeResult.BestFound(
                    sample, objective.evaluate(sample), TerminationReason.BudgetExhausted,
                    SolveStats(ls = LocalSearchStats(moves = SumResult(7.0))),
                )
            }
        }
        val alns = Alns(
            inner = inner,
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(InnerLsRepair()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 8,
            flipsPerIteration = 50,
        )

        val result = alns.minimize(selectObjective, LocalSearchParams(maxInstructions = 120))

        assertEquals(4, calls)
        assertSame(sample, result.assignment)
        assertEquals(7L, result.stats.alns.bootstrapLsMoves)
        assertEquals(21L, result.stats.alns.repairLsMoves)
        assertEquals(120L, result.stats.alns.outerAllowance)
        assertEquals(0.0, result.stats.ls.moves.sum)
    }

    @Test
    fun `retained complete repair counters count each fragment once`() {
        val problem = selectProblem()
        val incumbent = selectOptimum()
        var cumulativeNodes = 13L
        var recordedNodes = 0L
        val retained = object : RepairSearch {
            override val stats: SolveStats
                get() = SolveStats(search = SearchStats(nodes = SumResult(cumulativeNodes.toDouble())))
            override fun repair(
                assumptions: Assumptions,
                decisionBudget: Long,
                cutoff: Double,
                cancellation: Cancellation,
            ): Sample {
                cumulativeNodes += 5
                return incumbent
            }
        }
        val context = RepairContext(
            inner = NoFeasibleLs(problem),
            params = LocalSearchParams(),
            objective = selectObjective,
            pinAssumptions = Assumptions.None,
            incumbent = incumbent,
            freed = FreedVars(intArrayOf(0), IntArray(0)),
            repairSearch = retained,
            recordInnerWork = { nodes, moves ->
                recordedNodes += nodes
                assertEquals(0L, moves)
            },
        )

        repeat(2) { assertSame(incumbent, BacktrackRepair().repair(context)) }

        assertEquals(10L, recordedNodes)
        assertEquals(23L, cumulativeNodes)
    }

    /** The default menu's five arms at fixture-sized budgets. The production `deep` arm carries
     *  `flipsOverride = 5_000`, which replaces `maxFlips` rather than being capped by it, so a test that
     *  asserts nothing about repair depth still pays that arm in full whenever the bandit draws it. */
    private val scaledRepairOperators: List<RepairOperator> = listOf(
        InnerLsRepair(label = "standard"),
        InnerLsRepair(label = "quick", flipsOverride = 50L),
        InnerLsRepair(label = "deep", flipsOverride = 250L),
        GreedyConstructionRepair(),
        GreedyConstructionRepair(noise = 0.1),
    )

    /** The four cheapest weights of [SELECT_WEIGHTS] — the [selectProblem] optimum, so no repair can
     *  strictly improve on it and an adopted copy of it stays the run's reported best. */
    private fun selectOptimum(): Sample {
        val cheapest = SELECT_WEIGHTS.indices.sortedBy { SELECT_WEIGHTS[it] }.take(SELECT_MIN).toSet()
        return Sample(BooleanArray(SELECT_WEIGHTS.size) { it in cheapest }, LongArray(0))
    }

    /** Pick ≥ [SELECT_MIN] of 12 weighted booleans, minimising the picked weights. Wide enough that a
     *  starved run lands far from the optimum, so a published one is worth importing. */
    private fun selectProblem(): BakedProblem = Problem(
        numBoolVars = SELECT_WEIGHTS.size,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Cardinality(
                IntArray(SELECT_WEIGHTS.size) { Lit.make(it, true) },
                min = SELECT_MIN,
                max = SELECT_WEIGHTS.size,
            ),
        ),
    ).bake()

    private fun selectAlns(exchange: IncumbentExchange<Sample, Double>) = Alns(
        inner = LocalSearchSolver(selectProblem()),
        repairOperators = scaledRepairOperators,
        minDestroyFraction = 0.5,
        maxDestroyFraction = 0.5,
        maxIterations = 4,
        flipsPerIteration = 50L,
        acceptance = AcceptanceCriterion.BetterOrEqual,
        pooledIncumbents = exchange,
    )

    private val selectObjective = LinearObjective(boolWeights = SELECT_WEIGHTS)

    private fun selectExchangeHolding(sample: Sample) = IncumbentExchange.minimizing<Sample>()
        .apply { offer(sample, selectObjective.evaluate(sample)) }

    /** Warm-start every variable on and allow a single flip: the bootstrap incumbent is then the worst
     *  feasible selection, so a published optimum is unambiguously better than anything this run reached
     *  by itself and an adoption is visible in the reported assignment's identity. The per-iteration
     *  repairs keep their own [Alns.flipsPerIteration] budget. */
    private fun starvedBootstrap() = LocalSearchParams(
        maxFlips = 1L,
        randomSeed = 1L,
        initialAssignment = Sample(BooleanArray(SELECT_WEIGHTS.size) { true }, LongArray(0)),
    )

    @Test
    fun `adjacency related destroy stays inside connected components`() {
        // Two disconnected sub-problems sharing nothing:
        //   factor A: AtLeastOne over bool vars 0..3
        //   factor B: AtLeastOne over bool vars 4..7
        // Adjacency BFS from a seed in factor A should free vars only from A (until it
        // exhausts the component, at which point it re-seeds; with small `fraction` we
        // stay within one component).
        val fA = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val fB = Cardinality.atLeastOne(IntArray(4) { Lit.make(it + 4, true) })
        val problem = Problem(
            numBoolVars = 8,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(fA, fB),
        )
        val incumbent = Sample(BooleanArray(8) { false }, LongArray(0))
        val obj = LinearObjective(boolWeights = LongArray(8) { 1L })
        val freed = DestroyOperator.AdjacencyRelated.destroy(Random(0), problem.bake(), incumbent, obj, fraction = 0.25)
        assertEquals(2, freed.bools.size)
        val componentA = freed.bools.all { it in 0..3 }
        val componentB = freed.bools.all { it in 4..7 }
        assertTrue(componentA || componentB, "freed vars should be in one component: ${freed.bools.toList()}")
    }

    @Test
    fun `alns offers its accepted incumbents to the shared exchange`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val exchange = IncumbentExchange.minimizing<Sample>()
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 8,
            acceptance = AcceptanceCriterion.BetterOrEqual,
            pooledIncumbents = exchange,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 200L, randomSeed = 1L)).assignment
        assertNotNull(sample)
        val standing = assertNotNull(exchange.current(), "at least the initial incumbent must be installed")
        assertEquals(objective.evaluate(sample), standing.objective, "the exchange holds the run's best")
    }

    @Test
    fun `alns adopts a better published solution before destroying`() {
        val optimal = selectOptimum()
        val exchange = selectExchangeHolding(optimal)
        val sample = selectAlns(exchange).minimize(selectObjective, starvedBootstrap()).assignment
        assertSame(optimal, sample, "the published optimum, not a locally-found equal, is the reported best")
    }

    @Test
    fun `alns does not import a published solution under assumption pins`() {
        val optimal = selectOptimum()
        val exchange = selectExchangeHolding(optimal)
        val params = starvedBootstrap().copy(assumptions = Assumptions(bools = SELECT_DEAREST.associateWith { true }))
        val sample = selectAlns(exchange).minimize(selectObjective, params).assignment
        assertNotNull(sample)
        assertNotSame(optimal, sample, "a foreign full assignment may violate the pins, so none is imported")
    }

    @Test
    fun `greedy construction respects pinned vars`() {
        // 4 bools, exactly-one. Pin bools 0..2 false; only bool 3 free. Greedy must set
        // bool 3 = true (the only path to feasibility); pinned vars stay at 0.
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
        assertEquals(true, sample.bools[3], "free var 3 should be flipped to satisfy exactly-one")
    }

    @Test
    fun `backtrack repair stops at the run cancellation on both the reused and the fresh solve path`() {
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
                    params = LocalSearchParams(randomSeed = 0L, cancellation = Cancellation { true }),
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
    fun `inner ls repair honours flips override`() {
        val factor = Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 5L))
        val inner = LocalSearchSolver(problem.bake())
        val repair = InnerLsRepair(label = "test", flipsOverride = 100L)
        val context = RepairContext(
            inner = inner,
            params = LocalSearchParams(maxFlips = 999_999L, randomSeed = 0L),
            objective = objective,
            pinAssumptions = Assumptions.None,
            incumbent = Sample(booleanArrayOf(false, true), LongArray(0)),
            freed = FreedVars(intArrayOf(0, 1), IntArray(0)),
        )
        val s = repair.repair(context)
        assertNotNull(s)
        assertEquals(1.0, objective.evaluate(s))
    }

    @Test
    fun `acceptanceFor overrides the fixed acceptance and sees the initial objective`() {
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

    /** A local-search stub that never reaches feasibility — its minimize always returns [MinimizeResult.Unknown]. */
    private class NoFeasibleLs(override val problem: BakedProblem) : Optimizer<LocalSearchParams> {
        override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult =
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted)

        override fun solve(params: LocalSearchParams) = error("unused")
        override fun samples(params: LocalSearchParams) = error("unused")
        override fun enumerate(params: LocalSearchParams) = error("unused")
    }

    @Test
    fun `a cancelled backtrack bootstrap does not start a local search fallback`() {
        val problem = selectProblem()
        var expired = false
        val backtrack = object : Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult {
                expired = true
                return MinimizeResult.Unknown(TerminationReason.Cancelled)
            }
        }
        val alns = Alns(inner = NoFeasibleLs(problem), backtrack = backtrack)

        val result = alns.minimize(selectObjective, LocalSearchParams(cancellation = Cancellation { expired }))

        assertEquals(TerminationReason.Cancelled, (result as MinimizeResult.Unknown).reason)
    }

    @Test
    fun `maxInstructions caps the outer loop below maxIterations`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = longArrayOf(10L, 5L, 8L, 3L))
        val alns = Alns(
            inner = LocalSearchSolver(problem.bake()),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(InnerLsRepair()),
            minDestroyFraction = 0.5,
            maxDestroyFraction = 0.5,
            maxIterations = 8,
            flipsPerIteration = 50L,
        )
        alns.minimize(objective, LocalSearchParams(maxFlips = 200L, maxInstructions = 120L, randomSeed = 1L))
        assertEquals(3, alns.iterationLog.size, "120 / 50-per-iteration budget must stop the loop after 3 iterations")
    }

    private companion object {
        val SELECT_WEIGHTS = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L, 12L, 4L, 11L, 10L)
        const val SELECT_MIN = 4

        /** The four dearest weights of [SELECT_WEIGHTS]; pinning them true is a selection the published
         *  optimum (those four false) cannot satisfy. */
        val SELECT_DEAREST = listOf(8, 10, 11, 3)
    }
}
