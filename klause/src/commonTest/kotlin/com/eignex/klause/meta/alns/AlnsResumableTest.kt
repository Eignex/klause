package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.meta.alns.AlnsTestFixtures.NoFeasibleLs
import com.eignex.klause.meta.alns.AlnsTestFixtures.SELECT_DEAREST
import com.eignex.klause.meta.alns.AlnsTestFixtures.SELECT_WEIGHTS
import com.eignex.klause.meta.alns.AlnsTestFixtures.bootstrapAt
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectObjective
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectOptimum
import com.eignex.klause.meta.alns.AlnsTestFixtures.selectProblem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.IncumbentBootstrapSearch
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.RepairSearch
import com.eignex.klause.solver.RepairSearchStep
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AlnsResumableTest {
    @Test
    fun `complete bootstrap should resume one search until its first incumbent`() {
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
                        global: ExecutionControl,
                        sliceMillis: Long,
                        sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit,
                    ): MinimizeResult? {
                        slices++
                        if (slices == 1) return null
                        val result = MinimizeResult.BestFound(
                            sample,
                            objective.evaluate(sample),
                            TerminationReason.BudgetExhausted,
                            stats,
                        )
                        onIncumbent(result)
                        return result
                    }
                    override fun close() {
                        closes++
                    }
                }
            }
        }
        val alns = Alns(inner = NoFeasibleLs(problem), backtrack = backtrack, maxIterations = 0)
        val callbacks = mutableListOf<Sample>()
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))

        assertNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) { callbacks += it.sample })
        val result = assertNotNull(
            handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) { callbacks += it.sample },
        )
        handle.close()

        assertSame(sample, result.assignment)
        assertEquals(listOf(sample), callbacks)
        assertEquals(1, opened)
        assertEquals(1, closes)
        assertEquals(26L, result.stats.alns.bootstrapCpNodes)
    }

    @Test
    fun `local bootstrap should retain its walk across instruction slices`() {
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
                        global: ExecutionControl,
                        sliceMillis: Long,
                        sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit,
                    ): MinimizeResult? = error("instruction slice required")
                    override fun runInstructionSlice(
                        global: ExecutionControl,
                        sliceMillis: Long,
                        sliceInstructions: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit,
                    ): MinimizeResult? {
                        slices++
                        if (slices == 1) return null
                        return MinimizeResult.BestFound(
                            sample,
                            objective.evaluate(sample),
                            TerminationReason.BudgetExhausted,
                            stats,
                        ).also(onIncumbent)
                    }
                    override fun close() {
                        closes++
                    }
                }
            }
        }
        val alns = Alns(inner = inner, maxIterations = 0)
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))
        val bootstrap = assertIs<IncumbentBootstrapSearch>(handle)

        assertNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
        assertTrue(bootstrap.bootstrapPending)
        val result = assertNotNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})

        assertFalse(bootstrap.bootstrapPending)
        assertSame(sample, result.assignment)
        assertEquals(1, opened)
        assertEquals(1, closes)
        assertEquals(14L, handle.stats.alns.bootstrapLsMoves)
    }

    @Test
    fun `neighborhood slices should retain the accepted incumbent and acceptance policy`() {
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
            repairOperators = listOf(
                RepairOperator { context ->
                    incumbents += context.incumbent
                    Sample(
                        context.incumbent.bools.copyOf().also { it[SELECT_DEAREST[repairs.size]] = false },
                        LongArray(0),
                    ).also(repairs::add)
                },
            ),
            acceptanceFor = {
                acceptancePolicies++
                AcceptanceCriterion.Improving
            },
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 3,
            flipsPerIteration = 50,
        )
        val callbacks = mutableListOf<Sample>()
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(selectObjective, LocalSearchParams()))

        repeat(2) {
            assertNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE,
                50) { callbacks += it.sample })
        }
        val result = assertNotNull(
            handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) { callbacks += it.sample },
        )

        assertEquals(listOf(initial) + repairs.take(2), incumbents)
        assertEquals(listOf(initial) + repairs, callbacks)
        assertSame(repairs.last(), result.assignment)
        assertEquals(1, bootstraps)
        assertEquals(1, acceptancePolicies)
    }

    @Test
    fun `retained CP repairs should replace an expired slice token`() {
        val problem = selectProblem()
        var expired = false
        val handles = mutableListOf<RepairSearch>()
        val alns = Alns(
            inner = NoFeasibleLs(problem),
            backtrack = BacktrackSolver(problem),
            backtrackParams = BacktrackParams(randomSeed = 0L),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(
                RepairOperator { context ->
                    val repair = assertNotNull(context.repairSearch)
                    handles += repair
                    assertNotNull(
                        repair.repair(Assumptions.None, 100, Double.POSITIVE_INFINITY, context.params.cancellation),
                    )
                    expired = true
                    context.incumbent
                },
            ),
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 2,
            flipsPerIteration = 50,
        )
        val handle = assertIs<InstructionSlicedSearch>(alns.resumable(LinearObjective(), LocalSearchParams()))

        assertNull(handle.runInstructionSlice(ExecutionControl { expired }, Long.MAX_VALUE, 50) {})
        assertNotNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})

        assertEquals(2, handles.size)
        assertSame(handles[0], handles[1])
    }

    @Test
    fun `CP bootstrap and destroyed neighborhoods should respect caller pins`() {
        val problem = selectProblem()
        val pins = Assumptions(bools = SELECT_DEAREST.associateWith { true })
        for (resumable in listOf(false, true)) {
            var repairs = 0
            val alns = Alns(
                inner = NoFeasibleLs(problem),
                backtrack = BacktrackSolver(problem),
                backtrackParams = BacktrackParams(randomSeed = 0L),
                destroyOperators = listOf(DestroyOperator.Random),
                repairOperators = listOf(
                    RepairOperator { context ->
                        repairs++
                        assertTrue(SELECT_DEAREST.all { context.incumbent.bools[it] })
                        assertTrue(SELECT_DEAREST.none { it in context.freed.bools })
                        assertTrue(SELECT_DEAREST.all { context.pinAssumptions.boolValueOrNull(it) == true })
                        context.incumbent
                    },
                ),
                minDestroyFraction = 1.0,
                maxDestroyFraction = 1.0,
                maxIterations = 1,
            )
            val params = LocalSearchParams(assumptions = pins)

            val result = if (resumable) {
                alns.resumable(selectObjective, params).use {
                    assertNotNull(it.runSlice(ExecutionControl.Never, Long.MAX_VALUE) {})
                }
            } else {
                alns.minimize(selectObjective, params)
            }

            assertTrue(SELECT_DEAREST.all { assertNotNull(result.assignment).bools[it] })
            assertEquals(1, repairs)
        }
    }

    @Test
    fun `a bootstrap callback failure should close the child and release the ALNS handle`() {
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
                    global: ExecutionControl,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    onIncumbent(
                        MinimizeResult.BestFound(sample, objective.evaluate(sample), TerminationReason.BudgetExhausted),
                    )
                    return null
                }
                override fun close() {
                    closes++
                    error("child close")
                }
            }
        }
        val alns = Alns(inner = inner)
        val handle = alns.resumable(selectObjective, LocalSearchParams())

        val failure = assertFailsWith<IllegalArgumentException> {
            handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE) { throw IllegalArgumentException("callback") }
        }
        handle.close()
        alns.resumable(selectObjective, LocalSearchParams()).close()

        assertEquals("callback", failure.message)
        assertEquals("child close", failure.suppressedExceptions.single().message)
        assertEquals(1, closes)
    }

    @Test
    fun `closing a paused bootstrap should release both searches when one close fails`() {
        val problem = selectProblem()
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

        assertNull(handle.runSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
        val failure = assertFailsWith<IllegalStateException> { handle.close() }
        handle.close()
        alns.resumable(selectObjective, LocalSearchParams()).close()

        assertEquals("CP close", failure.message)
        assertEquals(1, cpCloses)
        assertEquals(1, lsCloses)
    }

    @Test
    fun `seeded ordinary repairs should retain a reproducible random stream`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        val seeds = List(2) { mutableListOf<Long?>() }
        repeat(2) { attempt ->
            val alns = Alns(
                inner = bootstrapAt(problem, sample),
                destroyOperators = listOf(DestroyOperator.Random),
                repairOperators = listOf(
                    RepairOperator { context ->
                        seeds[attempt] += context.params.randomSeed
                        context.incumbent
                    },
                ),
                minDestroyFraction = 1.0,
                maxDestroyFraction = 1.0,
                maxIterations = 3,
                flipsPerIteration = 1,
                rng = Random(3),
            )
            alns.minimize(selectObjective, LocalSearchParams(randomSeed = 3L, maxInstructions = 3))
        }

        assertEquals(3, seeds[0].filterNotNull().distinct().size)
        assertEquals(seeds[0], seeds[1])
    }

    @Test
    fun `the outer instruction cap should span retained neighborhood slices`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        val allowances = mutableListOf<Long>()
        val alns = Alns(
            inner = bootstrapAt(problem, sample),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(
                RepairOperator { context ->
                    allowances += context.params.maxFlips
                    context.incumbent
                },
            ),
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 8,
            flipsPerIteration = 50,
        )
        val handle = assertIs<InstructionSlicedSearch>(
            alns.resumable(selectObjective, LocalSearchParams(maxInstructions = 120)),
        )

        repeat(2) { assertNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {}) }
        val result = assertNotNull(handle.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})

        assertEquals(listOf(50L, 50L, 20L), allowances)
        assertEquals(120L, result.stats.alns.outerAllowance)
    }

    @Test
    fun `failed bootstrap should report both engines without charging an outer repair`() {
        val problem = selectProblem()
        val backtrack = object : Optimizer<BacktrackParams> by BacktrackSolver(problem) {
            override fun minimize(objective: LinearObjective, params: BacktrackParams) = MinimizeResult.Unknown(
                TerminationReason.BudgetExhausted,
                SolveStats(search = SearchStats(nodes = SumResult(13.0))),
            )
        }
        val inner = object : Optimizer<LocalSearchParams> by NoFeasibleLs(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams) = MinimizeResult.Unknown(
                TerminationReason.BudgetExhausted,
                SolveStats(ls = LocalSearchStats(moves = SumResult(7.0))),
            )
        }

        val result = Alns(inner = inner, backtrack = backtrack).minimize(
            selectObjective,
            LocalSearchParams(maxInstructions = 120),
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
    fun `inner repair counters should remain distinct from clipped outer allowances`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        var calls = 0
        val inner = object : Optimizer<LocalSearchParams> by LocalSearchSolver(problem) {
            override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
                calls++
                return MinimizeResult.BestFound(
                    sample,
                    objective.evaluate(sample),
                    TerminationReason.BudgetExhausted,
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
    fun `retained complete repair counters should count each fragment once`() {
        val problem = selectProblem()
        val incumbent = selectOptimum()
        var cumulativeNodes = 13L
        var recordedNodes = 0L
        val retained = object : RepairSearch {
            override val stats: SolveStats
                get() = SolveStats(search = SearchStats(nodes = SumResult(cumulativeNodes.toDouble())))
            override fun begin(
                assumptions: Assumptions,
                decisionBudget: Long,
                cutoff: Double,
                cancellation: ExecutionControl,
            ) {
                cumulativeNodes += 5
            }
            override fun runSlice(cancellation: ExecutionControl, millis: Long, work: Long) =
                RepairSearchStep(incumbent, TerminationReason.SearchExhausted)
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

    @Test
    fun `retained repair observations should keep scoped closure separate from a completion`() {
        val problem = selectProblem()
        val incumbent = selectOptimum()
        for (sample in listOf(null, incumbent)) {
            var observed: MinimizeResult? = null
            val retained = object : RepairSearch {
                override fun begin(assumptions: Assumptions, decisionBudget: Long, cutoff: Double,
                    cancellation: ExecutionControl) = Unit
                override fun runSlice(cancellation: ExecutionControl, millis: Long, work: Long) =
                    RepairSearchStep(sample, TerminationReason.SearchExhausted)
            }
            val context = RepairContext(NoFeasibleLs(problem), LocalSearchParams(), selectObjective,
                Assumptions.None, incumbent, FreedVars(intArrayOf(0), IntArray(0)), repairSearch = retained,
                recordRepairResult = { observed = it })

            val repaired = BacktrackRepair().repair(context)

            assertSame(sample, repaired)
            if (sample == null) {
                assertEquals(TerminationReason.SearchExhausted, assertIs<MinimizeResult.Unknown>(observed).reason)
            } else {
                assertEquals(TerminationReason.SearchExhausted, assertIs<MinimizeResult.BestFound>(observed).reason)
            }
        }
    }

    @Test
    fun `retained repairs should spend one outer solve allowance across slices`() {
        val problem = selectProblem()
        val sample = selectOptimum()
        val budget = NodeBudget(100L)
        val alns = Alns(
            inner = bootstrapAt(problem, sample),
            destroyOperators = listOf(DestroyOperator.Random),
            repairOperators = listOf(RepairOperator { it.incumbent }),
            minDestroyFraction = 1.0,
            maxDestroyFraction = 1.0,
            maxIterations = 8,
            flipsPerIteration = 50,
        )
        val handle = assertIs<InstructionSlicedSearch>(
            alns.resumable(selectObjective, LocalSearchParams(nodeBudget = budget)),
        )

        val result = handle.use {
            assertNull(it.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
            assertNotNull(it.runInstructionSlice(ExecutionControl.Never, Long.MAX_VALUE, 50) {})
        }

        assertEquals(80L, result.stats.alns.outerAllowance)
        assertEquals(100L, budget.spent)
    }
}
