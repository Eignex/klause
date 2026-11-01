package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.InstructionSlicedSolve
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.StatelessSession
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PortfolioWorkAccountingTest {
    @Test
    fun `scheduled optimization should charge auxiliary work beyond its native instructions`() {
        for (coordinates in listOf(false, true)) {
            val offers = ArrayList<Pair<Long, Long>>()
            val handle = object : InstructionSlicedSearch {
                override var chargedInstructions = 0L
                override var auxiliaryWork = 0L
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override fun runSlice(
                    global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? = error("scheduled slice required")
                override fun runInstructionSlice(
                    global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? = error("scheduled slice required")
                override fun runScheduledSlice(
                    global: Cancellation, sliceMillis: Long, sliceInstructions: Long, sliceWork: Long,
                    instructionsPerWork: Double, onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    offers += sliceInstructions to sliceWork
                    chargedInstructions += 3L
                    auxiliaryWork += 11L
                    return null
                }
            }
            val native = PortfolioWorker.ofMinimize(
                "priced", 0, countsInstructions = true, resumable = { handle },
            ) { _, _, _, _ -> error("retained search required") }
            val worker = if (coordinates) {
                native.objectiveCoordinateProposals(ModelIdentity.of(Any())) { null }
            } else native
            val arms = listOf(
                worker,
                PortfolioWorker.ofMinimize("done", 1) { _, _, _, _ -> sequenceOf(MinimizeResult.Infeasible()) },
            )

            val result = Portfolio(
                arms, fixedBandit(2, arm = 0), baseSliceWork = 7L, lsInstructionsPerWork = 1.5,
            ).use { it.minimize() }

            assertEquals(listOf(10L to 7L), offers)
            assertEquals(13L, result.stats.portfolio.arms[0].work)
        }
    }

    @Test
    fun `scheduled satisfaction should charge auxiliary work beyond its native instructions`() {
        var auxiliary = 0L
        val offers = ArrayList<Pair<Long, Long>>()
        val handle = object : InstructionSlicedSolve {
            override val auxiliaryWork: Long get() = auxiliary
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats(
                ls = LocalSearchStats(moves = SumResult(if (auxiliary == 0L) 0.0 else 3.0)),
            )
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
                error("scheduled slice required")
            override fun runInstructionSlice(
                global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
            ): SolveResult? = error("scheduled slice required")
            override fun runScheduledSlice(
                global: Cancellation, sliceMillis: Long, sliceInstructions: Long, sliceWork: Long,
                instructionsPerWork: Double,
            ): SolveResult? {
                offers += sliceInstructions to sliceWork
                auxiliary = 11L
                return null
            }
        }
        val arms = listOf(
            PortfolioWorker.ofSolve("priced", 0, countsInstructions = true, resumable = { handle }) { _, _ ->
                error("retained search required")
            },
            PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() },
        )

        val result = Portfolio(
            arms, fixedBandit(2, arm = 0), baseSliceWork = 7L, lsInstructionsPerWork = 1.5,
        ).use { it.solve() }

        assertEquals(listOf(10L to 7L), offers)
        assertEquals(13L, result.stats.portfolio.arms[0].work)
    }

    private fun lsArm(problem: Problem, objective: LinearObjective): PortfolioWorker = PortfolioWorker.of(
        "ls",
        0,
        LocalSearchSolver(problem.bake()).session(),
        LocalSearchParams(randomSeed = 0L),
        objective = objective,
        withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit) },
    )

    @Test
    fun `plateau reseeding should count observable and restart-configured searches`() {
        for (restarting in listOf(false, true)) {
            for (threshold in listOf(0, 2, 3, 4)) {
                var slices = 0
                val handle = ScriptedSearch({ it == 0 }) { slices++ }
                val worker = trackingWorker("plateau", 0, handle).also {
                    it.family = if (restarting) ArmFamily.Backtrack else ArmFamily.LocalSearch
                    it.restartingSearch = restarting
                }

                val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = threshold).use {
                    it.minimize(Cancellation { slices >= 13 })
                }

                assertEquals(threshold, result.stats.portfolio.reseedStaleThreshold)
                assertEquals(if (threshold == 0) 0L else 12L / threshold, result.stats.portfolio.arms.single().reseeds)
            }
        }
    }

    @Test
    fun `restarting traversal should reseed after equivalent base work segments`() {
        for ((divisor, growth) in listOf(1 to 1.0, 4 to 2.0)) {
            var slices = 0
            val offers = ArrayList<Long>()
            val source = ScriptedSearch({ it == 0 }) { slices++ }
            val handle = object : ResumableSearch by source {
                override val work: Long get() = slices * 5_000L / divisor
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    offers += sliceNodes
                    return source.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                }
            }
            val workers = listOf(
                trackingWorker("restarting", 0, handle).also { it.restartingSearch = true },
                trackingWorker("retained", 1, TrackingResumableSearch(null)),
            )
            val bandit = fixedBandit(2, arm = 0)

            val result = Portfolio(
                workers,
                bandit,
                baseSliceWork = 5_000L,
                maxSliceWork = 20_000L,
                sliceGrowth = growth,
            ).use {
                it.minimize(Cancellation { slices >= 1 + 3 * divisor })
            }

            assertEquals(1L, result.stats.portfolio.arms.first().reseeds)
            assertEquals(if (growth == 1.0) 5_000L else 20_000L, offers.max())
        }
    }

    @Test
    fun `zero work pauses should not count as plateau segments`() {
        for (restarting in listOf(false, true)) {
            var slices = 0
            val handle = TrackingResumableSearch(
                null,
                MinimizeResult.BestFound(
                    Sample(BooleanArray(0), LongArray(0)),
                    0.0,
                    TerminationReason.BudgetExhausted,
                ),
                onRun = { slices++ },
            )
            val worker = trackingWorker("paused", 0, handle).also {
                it.family = if (restarting) ArmFamily.Backtrack else ArmFamily.LocalSearch
                it.restartingSearch = restarting
            }

            val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = 1).use {
                it.minimize(Cancellation { slices >= 6 })
            }

            assertEquals(0L, result.stats.portfolio.arms.single().reseeds)
        }
    }

    private fun mixedWorkers(problem: Problem, objective: LinearObjective): List<PortfolioWorker> = listOf(
        lsArm(problem, objective),
        PortfolioWorker.of(
            "bt",
            1,
            BacktrackSolver(problem.bake()).session(),
            BacktrackParams(randomSeed = 1L),
            objective = objective,
            withBound = { p, supplier -> p.copy(objectiveBoundSupplier = supplier) },
        ),
    )

    @Test
    fun `a counted local search segment should report the work it spent`() {
        val cases = listOf(
            Triple(100L, 0L, 0L),
            Triple(100L, 75L, (75 / LS_INSTRUCTIONS_PER_WORK).toLong()),
            Triple(7L, 10L, 7L),
        )
        for ((allowance, moves, expected) in cases) {
            val arms = listOf(
                PortfolioWorker.ofSolve("ls", 0, countsInstructions = true) { _, _ ->
                    SolveResult.Unknown(
                        TerminationReason.BudgetExhausted,
                        SolveStats(ls = LocalSearchStats(moves = SumResult(moves.toDouble()))),
                    )
                },
                PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() },
            )

            val result = Portfolio.thompson(arms, baseSliceWork = allowance).use { it.solve() }

            assertEquals(expected, result.stats.portfolio.arms[0].work)
        }
    }

    @Test
    fun `resumed local search should spend the exact counted instruction allowance`() {
        val problem = Problem(
            3,
            0,
            emptyArray(),
            Array<Factor>(8) { mask -> Clause(IntArray(3) { v -> Lit.make(v, mask and (1 shl v) != 0) }) },
        )
        for (objective in listOf(null, LinearObjective())) {
            val arms = listOf(
                PortfolioWorker.of(
                    "ls",
                    0,
                    LocalSearchSolver(problem.bake()).session(),
                    LocalSearchParams(maxFlips = 100L, randomSeed = 3L),
                    objective = objective,
                    withInstructionBudget = { p, limit -> p.copy(maxInstructions = limit) },
                ),
                if (objective == null) {
                    PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() }
                } else {
                    PortfolioWorker.ofMinimize("done", 1) { _, _, _, _ -> sequenceOf(MinimizeResult.Infeasible()) }
                },
            )

            val result = Portfolio(
                arms,
                DiscountedThompson(arms.size, Random(0), Portfolio.DEFAULT_HALF_LIFE),
                baseSliceWork = 7L,
                lsInstructionsPerWork = 1.5,
            ).use { if (objective == null) it.solve().stats else it.minimize().stats }

            assertEquals(10.0, result.ls.moves.sum, "objective=$objective")
            assertEquals(7L, result.portfolio.arms[0].work, "objective=$objective")
        }
    }

    @Test
    fun `an ALNS segment should charge its outer allowance rather than inner moves`() {
        val arms = listOf(
            PortfolioWorker.ofSolve("lns", 0, countsInstructions = true) { _, _ ->
                SolveResult.Unknown(
                    TerminationReason.BudgetExhausted,
                    SolveStats(ls = LocalSearchStats(moves = SumResult(75.0))),
                )
            }.also { it.family = ArmFamily.Lns },
            PortfolioWorker.ofSolve("done", 1) { _, _ -> SolveResult.Unsat() },
        )

        val result = Portfolio.thompson(arms, baseSliceWork = 100L).use { it.solve() }

        assertEquals(100L, result.stats.portfolio.arms[0].work)
    }

    @Test
    fun `a retained ALNS arm should charge scheduling instructions without reporting inner moves`() {
        val handle = object : InstructionSlicedSearch {
            override var chargedInstructions = 0L
                private set
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats.EMPTY
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
                chargedInstructions += sliceInstructions
                return null
            }
        }
        val arms = listOf(
            PortfolioWorker.ofMinimize("lns", 0, countsInstructions = true, resumable = { handle }) { _, _, _, _ ->
                error("retained search required")
            }.also { it.family = ArmFamily.Lns },
            PortfolioWorker.ofMinimize("done", 1) { _, _, _, _ -> sequenceOf(MinimizeResult.Infeasible()) },
        )

        val result = Portfolio(
            arms,
            DiscountedThompson(arms.size, Random(0), Portfolio.DEFAULT_HALF_LIFE),
            baseSliceWork = 7L,
            lsInstructionsPerWork = 1.5,
        ).use { it.minimize() }

        assertEquals(10L, handle.chargedInstructions)
        assertEquals(7L, result.stats.portfolio.arms[0].work)
        assertEquals(0.0, result.stats.ls.moves.sum)
    }

    @Test
    fun `mixed sequential run should bound LS work to its counted segment allowance`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        val r = Portfolio.thompson(
            mixedWorkers(problem, objective),
            baseSliceWork = 7L,
        ).use { it.minimize() }

        val best = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(
            (7 * LS_INSTRUCTIONS_PER_WORK).toLong().toDouble(),
            best.stats.ls.moves.sum,
        )
    }

    @Test
    fun `every arm should probe at the base allowance however many run before it`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality.atLeastOne(IntArray(3) { Lit.make(it, true) })),
        )
        val objective = LinearObjective(boolWeights = longArrayOf(1L, 1L, 1L))
        val allowances = ArrayList<Long>()
        val arms = List(3) { i ->
            val session = LocalSearchSolver(problem.bake()).session()
            PortfolioWorker.ofMinimize(
                "ls$i",
                i,
                countsInstructions = true,
            ) { _, _, token, limit ->
                allowances += requireNotNull(limit)
                session.improvements(
                    objective,
                    LocalSearchParams(randomSeed = 0L, cancellation = token, maxInstructions = limit),
                )
            }
        }

        Portfolio.thompson(arms, baseSliceWork = 7L).use {
            it.minimize(Cancellation { allowances.size >= arms.size })
        }

        assertEquals(List(3) { (7 * LS_INSTRUCTIONS_PER_WORK).toLong() }, allowances)
    }

    @Test
    fun `a counted arm whose probe outlasts its time cap should hand the core to the next arm`() {
        val reached = ArrayList<Int>()
        val fallback = TimeSource.Monotonic.markNow() + 10.seconds

        endlessCountedPortfolio(reached, sliceMillis = 60_000L).use {
            it.minimize(Cancellation { reached.size >= 2 || fallback.hasPassedNow() })
        }

        assertEquals(listOf(0, 1), reached)
    }

    @Test
    fun `a counted segment past the probe should end at its time slice`() {
        val reached = ArrayList<Int>()
        val fallback = TimeSource.Monotonic.markNow() + 10.seconds

        endlessCountedPortfolio(reached, sliceMillis = 20L).use {
            it.minimize(Cancellation { reached.size >= 4 || fallback.hasPassedNow() })
        }

        assertEquals(4, reached.size)
    }

    @Test
    fun `a short budget should still split into segments however long the slices are`() {
        val reached = ArrayList<Int>()
        val deadline = Cancellation.until(TimeSource.Monotonic.markNow() + 200.milliseconds)

        endlessCountedPortfolio(reached, sliceMillis = 60_000L, probeMillis = 60_000L).use {
            it.minimize(deadline or Cancellation { reached.size >= 4 })
        }

        assertEquals(4, reached.size)
    }

    @Test
    fun `mixed sequential runs should reproduce their counted work`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val objective = LinearObjective()
        fun run() = Portfolio.thompson(
            mixedWorkers(problem, objective),
            baseSliceWork = 7L,
        ).use { it.minimize() }

        val first = run()
        val second = run()

        assertIs<MinimizeResult.Optimal>(first)
        assertIs<MinimizeResult.Optimal>(second)
        assertEquals(first.stats.ls.moves, second.stats.ls.moves)
        assertEquals(first.stats.search.nodes, second.stats.search.nodes)
    }

    @Test
    fun `handle construction work should be charged once across resumed segments`() {
        var slices = 0
        val scripted = ScriptedSearch({ false }) { slices++ }
        val handle = object : ResumableSearch by scripted {
            override val initialWork: Long = 10L
            override val work: Long get() = initialWork + scripted.work
        }
        val worker = trackingWorker("initialized", 0, handle)

        val result = Portfolio.thompson(listOf(worker)).use {
            it.minimize(Cancellation { slices >= 2 })
        }

        assertEquals(handle.work, result.stats.portfolio.arms.single().work)
        assertEquals(10L, result.stats.portfolio.arms.single().initializationWork)
    }

    @Test
    fun `opening cost should reduce the offered search allowance`() {
        var openingDeadline: ComparableTimeMark? = null
        var requestedMillis: Long? = null
        var remainingMillis: Long? = null
        var slices = 0
        val handle = object : ResumableSearch by TrackingResumableSearch(null) {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                slices++
                requestedMillis = sliceMillis
                return MinimizeResult.Unknown(TerminationReason.Unsupported)
            }
        }
        val solver = object : ResumableOptimizer<BacktrackParams> by TrackingResumableOptimizer(handle) {
            override fun session() = StatelessSession(this)

            override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch {
                openingDeadline = params.cancellation.deadline()
                val until = TimeSource.Monotonic.markNow() + 5.milliseconds
                do {
                    val waiting = !until.hasPassedNow()
                } while (waiting)
                remainingMillis = (checkNotNull(openingDeadline) - TimeSource.Monotonic.markNow())
                    .inWholeMilliseconds.coerceAtLeast(0L)
                return handle
            }
        }
        val workers = listOf(
            PortfolioWorker.of("opening", 0, solver.session(), BacktrackParams(), LinearObjective()),
            trackingWorker(
                "sibling",
                1,
                TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.Unsupported)),
            ),
        )

        Portfolio.thompson(workers).use { it.minimize(Cancellation.after(10.seconds)) }

        assertEquals(1, slices)
        assertTrue(checkNotNull(requestedMillis) <= checkNotNull(remainingMillis) + 1L)
    }
}
