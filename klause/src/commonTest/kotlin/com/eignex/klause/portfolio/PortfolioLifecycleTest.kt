package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.ResumableSolver
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.StatelessSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.RunStats
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PortfolioLifecycleTest {
    @Test
    fun `run duration should include every one shot segment and witness verification`() {
        for (optimize in listOf(false, true)) {
            val clock = TestTimeSource()
            val sample = Sample(BooleanArray(0), LongArray(0))
            val workers = List(2) { arm ->
                val stats = SolveStats(
                    run = RunStats(backend = "worker$arm", wallMs = 1, timedOut = arm == 0),
                    search = SearchStats(nodes = SumResult((arm + 1).toDouble())),
                )
                if (optimize) {
                    PortfolioWorker.ofMinimize("worker$arm", arm) { _, _, _, _ ->
                        clock += (11 + arm * 2).milliseconds
                        sequenceOf(
                            if (arm == 0) {
                                MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)
                            } else {
                                MinimizeResult.Optimal(sample, 0.0, stats)
                            },
                        )
                    }
                } else {
                    PortfolioWorker.ofSolve("worker$arm", arm) { _, _ ->
                        clock += (11 + arm * 2).milliseconds
                        if (arm == 0) {
                            SolveResult.Unknown(TerminationReason.BudgetExhausted, stats)
                        } else {
                            SolveResult.Sat(sample, stats)
                        }
                    }
                }
            }
            val portfolio = Portfolio.thompson(workers, witnessCheck = { _, _ ->
                clock += 5.milliseconds
                null
            }).also { it.runTimeSource = clock }

            val stats = portfolio.use { if (optimize) it.minimize().stats else it.solve().stats }

            assertEquals(29L, stats.run.wallMs, "optimize=$optimize")
            assertEquals(3.0, stats.search.nodes.sum)
            assertEquals("mixed", stats.run.backend)
            assertTrue(stats.run.timedOut)
        }
    }

    @Test
    fun `run duration should include retained handle initialization and disposal`() {
        for (optimize in listOf(false, true)) {
            val clock = TestTimeSource()
            var slices = 0
            val worker = if (optimize) {
                val tracked = TrackingResumableSearch(null, onRun = {
                    clock += 5.milliseconds
                    slices++
                })
                val handle = object : ResumableSearch by tracked {
                    override fun close() {
                        tracked.close()
                        clock += 11.milliseconds
                    }
                }
                PortfolioWorker.ofMinimize("retained", 0, resumable = {
                    clock += 7.milliseconds
                    handle
                }) { _, _, _, _ -> error("the retained handle must resume") }
            } else {
                val tracked = CountingResumableSolve(2)
                val handle = object : ResumableSolve by tracked {
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
                        clock += 5.milliseconds
                        slices++
                        return tracked.runSlice(global, sliceMillis, sliceNodes)
                    }

                    override fun close() {
                        clock += 11.milliseconds
                    }
                }
                PortfolioWorker.ofSolve("retained", 0, resumable = {
                    clock += 7.milliseconds
                    handle
                }) { _, _ -> error("the retained handle must resume") }
            }
            val portfolio = Portfolio.thompson(listOf(worker)).also { it.runTimeSource = clock }

            val stats = portfolio.use {
                val stop = Cancellation { slices >= 2 }
                if (optimize) it.minimize(stop).stats else it.solve(stop).stats
            }

            assertEquals(28L, stats.run.wallMs, "optimize=$optimize")
        }
    }

    @Test
    fun `a caught reseed close failure should retire the arm and retain its incumbent`() {
        var slices = 0
        val sample = Sample(BooleanArray(0), LongArray(0))
        val handle = TrackingResumableSearch(
            null,
            MinimizeResult.BestFound(sample, 0.0, TerminationReason.BudgetExhausted),
            onRun = { slices++ },
            closeFailure = "close failure",
        )
        val spending = object : ResumableSearch by handle {
            override val work: Long get() = slices.toLong()
        }

        val result = Portfolio.thompson(
            listOf(trackingWorker("failed", 7, spending).also { it.family = ArmFamily.LocalSearch }),
            reseedStaleThreshold = 1,
        ).use { it.minimize(Cancellation { slices >= 3 }) }

        assertEquals(sample, assertIs<MinimizeResult.BestFound>(result).sample)
        val arm = result.stats.portfolio.arms.single()
        val diagnostic = assertNotNull(arm.failure)
        assertEquals("close", diagnostic.phase)
        assertEquals("close failure", diagnostic.message)
        assertEquals(1L, arm.failures)
        assertEquals(0L, arm.reseeds)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `reseeds should retain each handle's final counters without counting snapshots twice`() {
        for (threshold in listOf(0, 3)) {
            var slices = 0
            var opened = 0
            var closed = 0
            var expectedNodes = 0.0
            var expectedMoves = 0.0
            val worker = PortfolioWorker.ofMinimize(
                "plateau",
                0,
                resumable = {
                    opened++
                    object : ResumableSearch {
                        private var runs = 0
                        private var spent = 0L
                        private var finished = false
                        private val observations: Double get() = ((runs + 1) / 2).toDouble()
                        override val isDone: Boolean get() = false
                        override val work: Long get() = spent
                        override val stats: SolveStats get() = SolveStats(
                            search = SearchStats(nodes = SumResult(7.0 + observations + if (finished) 3.0 else 0.0)),
                            ls = LocalSearchStats(moves = SumResult(observations * 2.0)),
                        )

                        override fun runSlice(
                            global: Cancellation,
                            sliceMillis: Long,
                            sliceNodes: Long,
                            onIncumbent: (MinimizeResult.WithSample) -> Unit,
                        ): MinimizeResult? {
                            runs++
                            spent += sliceNodes.coerceAtLeast(1L)
                            if (++slices == 1) {
                                onIncumbent(
                                    MinimizeResult.BestFound(
                                        Sample(BooleanArray(0), LongArray(0)),
                                        0.0,
                                        TerminationReason.BudgetExhausted,
                                    ),
                                )
                            }
                            return null
                        }

                        override fun close() {
                            assertEquals(false, finished)
                            finished = true
                            closed++
                            expectedNodes += stats.search.nodes.sum
                            expectedMoves += stats.ls.moves.sum
                        }
                    }
                },
            ) { _, _, _, _ -> error("the retained handle must resume") }
                .also { it.family = ArmFamily.LocalSearch }

            val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = threshold).use {
                it.minimize(Cancellation { slices >= 14 })
            }

            assertIs<MinimizeResult.BestFound>(result)
            assertEquals(opened, closed)
            assertEquals(if (threshold == 0) 1 else 1 + 12 / threshold, opened)
            assertEquals(expectedNodes, result.stats.search.nodes.sum)
            assertEquals(expectedMoves, result.stats.ls.moves.sum)
        }
    }

    @Test
    fun `ordinary arm diagnostics should preserve the surviving optimization verdict`() {
        for (phase in listOf("opening", "slice", "one-shot")) {
            val failedHandle = TrackingResumableSearch(null, onRun = { error("arm failure") })
            val worker = PortfolioWorker.ofMinimize(
                "failed",
                17,
                resumable = when (phase) {
                    "opening" -> { _ -> error("arm failure") }
                    "slice" -> { _ -> failedHandle }
                    else -> null
                },
            ) { _, _, _, _ -> sequence { error("arm failure") } }
            val sample = Sample(BooleanArray(0), LongArray(0))
            val sibling = TrackingResumableSearch(MinimizeResult.Optimal(sample, 0.0))

            val result = Portfolio.thompson(listOf(worker, trackingWorker("sibling", 18, sibling))).use {
                it.minimize()
            }

            assertEquals(sample, assertIs<MinimizeResult.Optimal>(result).sample)
            val arm = result.stats.portfolio.arms.first()
            val diagnostic = assertNotNull(arm.failure)
            assertEquals(1L, arm.failures)
            assertEquals(0L, arm.faults)
            assertEquals(17, diagnostic.armId)
            assertEquals("IllegalStateException", diagnostic.type)
            assertEquals("arm failure", diagnostic.message)
            assertEquals(phase, diagnostic.phase)
            assertEquals(1L, diagnostic.segment)
            assertTrue("IllegalStateException: arm failure" in diagnostic.trace)
            assertNull(result.stats.portfolio.arms.last().failure)
        }
    }

    @Test
    fun `arm exception text should be bounded in retained diagnostics`() {
        val worker = PortfolioWorker.ofMinimize("failed", 0) { _, _, _, _ ->
            sequence { error("x".repeat(10000)) }
        }

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        val diagnostic = assertNotNull(result.stats.portfolio.arms.single().failure)
        assertEquals(1024, assertNotNull(diagnostic.message).length)
        assertEquals(4096, diagnostic.trace.length)
    }

    @Test
    fun `accepted callback failure should remain primary after retained handle cleanup`() {
        val sample = Sample(BooleanArray(0), longArrayOf(1))
        val handle = TrackingResumableSearch(
            MinimizeResult.Optimal(sample, 1.0),
            MinimizeResult.BestFound(sample, 1.0, TerminationReason.BudgetExhausted),
            closeFailure = "close failure",
        )
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failing", 0, handle)))

        val failure = assertFailsWith<IllegalStateException> {
            portfolio.minimize(
                Cancellation.Never,
                null,
                PortfolioIncumbents.floating(),
                onAccepted = { error("accepted output failure") },
            )
        }

        assertEquals("accepted output failure", failure.message)
        assertEquals(1, handle.closes)
        assertEquals("close failure", failure.suppressedExceptions.single().message)
    }

    @Test
    fun `retired arm should not be reopened when the bandit keeps selecting it`() {
        var dirtyRuns = 0
        var activeRuns = 0
        val dirty = TrackingResumableSearch(
            MinimizeResult.Unknown(TerminationReason.Unsupported),
            onRun = { dirtyRuns++ },
        )
        val sample = Sample(BooleanArray(0), LongArray(0))
        val active = object : ResumableSearch {
            var closed = 0
            override val stats: SolveStats get() = SolveStats.EMPTY
            override val isDone: Boolean get() = activeRuns == 2
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                activeRuns++
                return if (activeRuns == 1) null else MinimizeResult.Optimal(sample, 0.0)
            }
            override fun close() {
                closed++
            }
        }
        val bandit = fixedBandit(2, arm = 0)
        val portfolio = Portfolio(
            listOf(trackingWorker("dirty", 0, dirty), trackingWorker("active", 1, active)),
            bandit,
        )
        var polls = 0
        assertIs<MinimizeResult.Optimal>(portfolio.minimize(Cancellation { ++polls > 20 }))
        assertEquals(1, dirtyRuns)
        assertEquals(2, activeRuns)
        assertEquals(1, dirty.closes)
        assertEquals(1, active.closed)
    }

    @Test
    fun `failed resumable arm should be closed without rescheduling`() {
        var runs = 0
        val failed = TrackingResumableSearch(
            null,
            onRun = {
                runs++
                error("run failure")
            },
        )
        var polls = 0
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failed", 0, failed)))
        assertIs<MinimizeResult.Unknown>(portfolio.minimize(Cancellation { ++polls > 20 }))
        assertEquals(1, runs)
        assertEquals(1, failed.closes)
    }

    @Test
    fun `an output callback failure should remain the primary failure during cleanup`() {
        val sample = Sample(BooleanArray(0), longArrayOf(1))
        val handle = TrackingResumableSearch(
            MinimizeResult.Optimal(sample, 1.0),
            MinimizeResult.BestFound(sample, 1.0, TerminationReason.BudgetExhausted),
            closeFailure = "close failure",
        )
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failing", 0, handle)))

        val failure = assertFailsWith<IllegalStateException> {
            portfolio.minimize { error("output failure") }
        }

        assertEquals("output failure", failure.message)
        assertEquals(1, handle.closes)
        assertEquals("close failure", failure.suppressedExceptions.single().message)
    }

    @Test
    fun `terminal arm should close a paused sibling handle`() {
        val paused = TrackingResumableSearch(null)
        val sample = Sample(BooleanArray(0), LongArray(0))
        val terminal = TrackingResumableSearch(
            result = MinimizeResult.Optimal(sample, 0.0),
            incumbent = MinimizeResult.BestFound(sample, 0.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = Portfolio.thompson(
            listOf(trackingWorker("paused", 0, paused), trackingWorker("terminal", 1, terminal)),
        )

        assertIs<MinimizeResult.Optimal>(portfolio.minimize())

        assertEquals(1, paused.closes)
        assertEquals(1, terminal.closes)
    }

    @Test
    fun `global cancellation should close a paused handle`() {
        var cancelled = false
        val paused = TrackingResumableSearch(null, onRun = { cancelled = true })
        val portfolio = Portfolio.thompson(listOf(trackingWorker("paused", 0, paused)))

        assertIs<MinimizeResult.Unknown>(portfolio.minimize(Cancellation { cancelled }))

        assertEquals(1, paused.closes)
    }

    @Test
    fun `owner failure should remain primary when handle close fails`() {
        val handle = TrackingResumableSearch(
            result = null,
            statsFailure = "stats failure",
            closeFailure = "close failure",
        )
        val portfolio = Portfolio.thompson(listOf(trackingWorker("failing", 0, handle)))

        val failure = assertFailsWith<IllegalStateException> { portfolio.minimize() }

        assertEquals("stats failure", failure.message)
        assertEquals(1, handle.closes)
    }

    @Test
    fun `handle close failure should not prevent closing remaining handles`() {
        val failing = TrackingResumableSearch(null, closeFailure = "close failure")
        val sample = Sample(BooleanArray(0), LongArray(0))
        val terminal = TrackingResumableSearch(
            result = MinimizeResult.Optimal(sample, 0.0),
            incumbent = MinimizeResult.BestFound(sample, 0.0, TerminationReason.BudgetExhausted),
        )
        val portfolio = Portfolio.thompson(
            listOf(trackingWorker("failing", 0, failing), trackingWorker("terminal", 1, terminal)),
        )

        val failure = assertFailsWith<IllegalStateException> { portfolio.minimize() }

        assertEquals("close failure", failure.message)
        assertEquals(1, failing.closes)
        assertEquals(1, terminal.closes)
    }

    @Test
    fun `a failing one-shot arm should be retired after one segment and reported`() {
        val failing = ThrowingSolver()
        val resumable = CountingResumableSolver(slicesToVerdict = 20)
        val workers = listOf(
            PortfolioWorker.of("failing", 0, failing.session(), BacktrackParams()),
            PortfolioWorker.of("bt", 1, resumable.session(), BacktrackParams()),
        )

        val r = Portfolio.thompson(workers).use { it.solve() }

        assertIs<SolveResult.Sat>(r)
        assertEquals(1, failing.solves)
        assertEquals(listOf(1L, 0L), r.stats.portfolio.arms.map { it.failures })
        val diagnostic = assertNotNull(r.stats.portfolio.arms.first().failure)
        assertEquals("solve failure", diagnostic.message)
        assertEquals("IllegalStateException", diagnostic.type)
        assertEquals("one-shot", diagnostic.phase)
        assertEquals(0, diagnostic.armId)
        assertEquals(0L, r.stats.portfolio.arms.first().faults)
        assertNull(r.stats.portfolio.arms.last().failure)
    }

    @Test
    fun `nonresumable session optimization should retain stacked assumptions`() {
        val solver = BacktrackSolver(Problem(0, 1, arrayOf(IntDomain(0L, 1L)), emptyArray()).bake())
        val session = object : StatelessSession<BacktrackParams>(solver) {
            override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch? = null
        }
        session.push(Assumptions.None.withInt(0, 1L))
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val worker = PortfolioWorker.of("pinned", 0, session, BacktrackParams(), objective)

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize(Cancellation.Never) }

        assertEquals(1.0, assertIs<MinimizeResult.Optimal>(result).objectiveValue)
    }

    @Test
    fun `cancelled optimization construction should retire without stopping siblings`() {
        var openings = 0
        val sibling = TrackingResumableSearch(MinimizeResult.Optimal(Sample(BooleanArray(0), LongArray(0)), 0.0))
        val solver = object : ResumableOptimizer<BacktrackParams> by TrackingResumableOptimizer(sibling) {
            override fun session() = StatelessSession(this)

            override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch {
                openings++
                assertTrue(params.cancellation.deadline() != null)
                throw SearchInitializationCancelled(SolveStats.EMPTY, 17L)
            }
        }
        val worker = PortfolioWorker.of("opening", 0, solver.session(), BacktrackParams(), LinearObjective())
        val portfolio = Portfolio.thompson(listOf(worker, trackingWorker("sibling", 1, sibling)))

        val result = portfolio.use { it.minimize(Cancellation.Never) }

        assertIs<MinimizeResult.Optimal>(result)
        assertEquals(1, openings)
        val arm = result.stats.portfolio.arms.first()
        assertEquals(17L, arm.work)
        assertEquals(17L, arm.initializationWork)
        assertEquals(1L, arm.initializationCancelled)
        assertEquals(0L, arm.failures)
        assertEquals(1, sibling.closes)
    }

    @Test
    fun `failed satisfaction construction should leave deferred siblings eligible`() {
        var openings = 0
        val solver = object : ResumableSolver<BacktrackParams> by CountingResumableSolver(1) {
            override fun session() = StatelessSession(this)

            override fun resumableSolve(params: BacktrackParams): ResumableSolve {
                openings++
                assertTrue(params.cancellation.deadline() != null)
                error("opening failure")
            }
        }
        val sibling = CountingResumableSolver(1)
        val workers = listOf(
            PortfolioWorker.of("opening", 0, solver.session(), BacktrackParams()),
            PortfolioWorker.of("sibling", 1, sibling.session(), BacktrackParams()).also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers).use { it.solve(Cancellation.Never) }

        assertIs<SolveResult.Sat>(result)
        assertEquals(1, openings)
        assertEquals(1L, result.stats.portfolio.arms.first().failures)
        assertEquals(1, sibling.opened.size)
    }
}
