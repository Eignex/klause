package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SliceWorkAccounting
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TestTimeSource

class PortfolioProgressTest {
    @Test
    fun `worker exit should retain the native return before proof reinterpretation`() {
        val sample = Sample(BooleanArray(0), LongArray(0))
        for (native in listOf(MinimizeResult.Unknown(TerminationReason.SearchExhausted),
            MinimizeResult.BestFound(sample, 1.0, TerminationReason.SearchExhausted))) {
            val events = mutableListOf<PortfolioEvent>()
            val search = TrackingResumableSearch(native)
            val worker = trackingWorker("proof", 0, search).also { it.controlsObjectiveCutoff = true }

            val result = Portfolio.thompson(listOf(worker),
                onProgress = events::add).use { it.minimize(Cancellation.Never) }

            if (native is MinimizeResult.Unknown) assertIs<MinimizeResult.Infeasible>(result)
            else assertIs<MinimizeResult.Optimal>(result)
            val exit = events.filterIsInstance<PortfolioEvent.WorkerExit>().single()
            assertEquals("terminal", exit.cause)
            assertEquals(if (native is MinimizeResult.Unknown) "unknown" else "best-found", exit.outcome)
            assertEquals(TerminationReason.SearchExhausted, exit.reason)
            assertNull(exit.cutoffBits)
            assertNull(exit.incumbentVersion)
            assertEquals(1, search.closes)
        }
    }

    @Test
    fun `worker exit should bind cutoff bits to the version actually read`() {
        val events = mutableListOf<PortfolioEvent>()
        val reads = mutableListOf<Double>()
        val worker = PortfolioWorker.ofMinimize("reader", 0, resumable = { bound ->
            ScriptedSearch({ true }, perSlice = 2) { reads += bound() }
        }) { _, _, _, _ -> error("retained search required") }

        Portfolio.thompson(listOf(worker), baseSliceWork = 1, maxSliceWork = 1,
            sliceGrowth = 1.0, reseedStaleThreshold = 0,
            diagnostics = PortfolioDiagnostics(6), onProgress = events::add).use {
            it.minimize(Cancellation.Never)
        }

        assertEquals(listOf(Double.POSITIVE_INFINITY, 998.0), reads)
        val exit = events.filterIsInstance<PortfolioEvent.WorkerExit>().single()
        assertEquals("invocation-close", exit.cause)
        assertEquals("paused", exit.outcome)
        assertNull(exit.reason)
        assertEquals(998.0.toRawBits(), exit.cutoffBits)
        assertEquals(2L, exit.incumbentVersion)
        assertEquals(4L, events.filterIsInstance<PortfolioEvent.Lifecycle>()
            .last { it.kind == PortfolioLifecycleKind.INCUMBENT_AVAILABLE }.version)
    }

    @Test
    fun `an empty pool cutoff read should differ from no cutoff read`() {
        val events = mutableListOf<PortfolioEvent>()
        val reader = PortfolioWorker.ofMinimize("reader", 0, resumable = { bound ->
            TrackingResumableSearch(null, onRun = { bound() })
        }) { _, _, _, _ -> error("retained search required") }
        val unread = trackingWorker("unread", 1, TrackingResumableSearch(null))

        Portfolio.thompson(listOf(reader, unread), baseSliceWork = 1, maxSliceWork = 1,
            diagnostics = PortfolioDiagnostics(2),
            onProgress = events::add).use { it.minimize(Cancellation.Never) }

        val exits = events.filterIsInstance<PortfolioEvent.WorkerExit>().sortedBy { it.arm }
        assertEquals(listOf("reader", "unread"), exits.map { it.worker })
        assertEquals(listOf(Double.POSITIVE_INFINITY.toRawBits(), null), exits.map { it.cutoffBits })
        assertEquals(listOf(0L, null), exits.map { it.incumbentVersion })
    }

    @Test
    fun `failed worker should report failure without a native termination reason`() {
        val events = mutableListOf<PortfolioEvent>()
        val search = TrackingResumableSearch(null, onRun = { error("worker failed") })

        Portfolio.thompson(listOf(trackingWorker("failing", 0, search)),
            onProgress = events::add).use { it.minimize(Cancellation.Never) }

        val exit = events.filterIsInstance<PortfolioEvent.WorkerExit>().single()
        assertEquals("failure", exit.cause)
        assertEquals("failed", exit.outcome)
        assertNull(exit.reason)
        assertEquals(1, search.closes)
    }

    @Test
    fun `interrupted opening should report initialization cancellation`() {
        val events = mutableListOf<PortfolioEvent>()
        val worker = PortfolioWorker.ofMinimize("opening", 0, resumable = {
            throw SearchInitializationCancelled(SolveStats.EMPTY, 17L)
        }) { _, _, _, _ -> error("opening must not fall back") }

        Portfolio.thompson(listOf(worker), onProgress = events::add).use {
            it.minimize(Cancellation.Never)
        }

        val exit = events.filterIsInstance<PortfolioEvent.WorkerExit>().single()
        assertEquals("initialization-cancelled", exit.cause)
        assertEquals("initialization-cancelled", exit.outcome)
        assertNull(exit.reason)
        assertNull(exit.cutoffBits)
    }

    @Test
    fun `satisfaction exit should retain its native incomplete reason`() {
        val events = mutableListOf<PortfolioEvent>()
        val worker = PortfolioWorker.ofSolve("declined", 0, countsInstructions = true) { _, _ ->
            SolveResult.Unknown(TerminationReason.Unsupported)
        }

        Portfolio.thompson(listOf(worker), baseSliceWork = 1, diagnostics = PortfolioDiagnostics(1),
            onProgress = events::add).use { it.solve(Cancellation.Never) }

        val exit = events.filterIsInstance<PortfolioEvent.WorkerExit>().single()
        assertEquals("invocation-close", exit.cause)
        assertEquals("unknown", exit.outcome)
        assertEquals(TerminationReason.Unsupported, exit.reason)
        assertNull(exit.cutoffBits)
        assertNull(exit.incumbentVersion)
    }

    @Test
    fun `a failed exit observer should release every retained handle`() {
        val searches = listOf(TrackingResumableSearch(null), TrackingResumableSearch(null))
        val workers = searches.mapIndexed { i, search -> trackingWorker("worker-$i", i, search) }

        assertFailsWith<IllegalStateException> {
            Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1,
                diagnostics = PortfolioDiagnostics(2), onProgress = {
                if (it is PortfolioEvent.WorkerExit) error("sink failed")
            }).use { it.minimize(Cancellation.Never) }
        }

        assertEquals(listOf(1, 1), searches.map { it.closes })
    }

    @Test
    fun `first improvement continuation should report a retained debt repayment only once`() {
        val events = mutableListOf<PortfolioEvent>()
        val base = ScriptedSearch({ it == 0 }) { }
        var slices = 0
        val search = object : ResumableSearch by base, SliceWorkAccounting {
            override var repaidWork: Long = 0L
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                repaidWork = if (slices++ == 1) sliceNodes else 0L
                return if (repaidWork > 0L) null else
                    base.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
            }
        }

        val result = Portfolio.thompson(listOf(trackingWorker("producer", 0, search)),
            baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0, reseedStaleThreshold = 0,
            diagnostics = PortfolioDiagnostics(5), onProgress = events::add).use {
            it.minimize(Cancellation.Never)
        }

        val observed = events.filterIsInstance<PortfolioEvent.Continuation>().single()
        assertEquals("producer", observed.worker)
        assertTrue(observed.retained)
        assertEquals(0L, observed.work)
        assertEquals(1L, observed.offeredWork)
        assertEquals(1L, observed.repaidWork)
        assertEquals(false, observed.done)
        assertEquals("paused", observed.outcome)
        assertTrue(result.stats.portfolio.arms.single().work > 1L)
    }

    @Test
    fun `disabled continuation reporting should not inspect handle accounting`() {
        val base = ScriptedSearch({ it == 0 }) { }
        val search = object : ResumableSearch by base, SliceWorkAccounting {
            override val repaidWork: Long get() = error("reporting is disabled")
        }

        val result = Portfolio.thompson(listOf(trackingWorker("producer", 0, search)),
            baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0, reseedStaleThreshold = 0,
            diagnostics = PortfolioDiagnostics(5)).use { it.minimize(Cancellation.Never) }

        assertTrue(result.stats.portfolio.arms.single().work > 1L)
    }

    @Test
    fun `a failed continuation observer should release its retained handle`() {
        val search = TrackingResumableSearch(null,
            MinimizeResult.BestFound(Sample(BooleanArray(0), LongArray(0)), 1.0, TerminationReason.BudgetExhausted))

        assertFailsWith<IllegalStateException> {
            Portfolio.thompson(listOf(trackingWorker("producer", 0, search)),
                baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
                diagnostics = PortfolioDiagnostics(10), onProgress = {
                    if (it is PortfolioEvent.Continuation) error("sink failed")
                }).use { it.minimize(Cancellation.Never) }
        }

        assertEquals(1, search.closes)
    }

    @Test
    fun `each accepted incumbent should notify its version`() {
        val events = mutableListOf<PortfolioEvent>()
        val worker = trackingWorker("finder", 0, ScriptedSearch({ true }, perSlice = 3) { })

        Portfolio.thompson(listOf(worker), baseSliceWork = 1,
            diagnostics = PortfolioDiagnostics(1), onProgress = events::add).use {
            it.minimize(Cancellation.Never)
        }

        val available = events.filterIsInstance<PortfolioEvent.Lifecycle>()
            .filter { it.kind == PortfolioLifecycleKind.INCUMBENT_AVAILABLE }
        assertEquals(listOf(1L, 2L, 3L), available.map { it.version })
    }

    @Test
    fun `repeated incumbents should preserve the first availability timestamp`() {
        val clock = TestTimeSource()
        val worker = trackingWorker("finder", 0, ScriptedSearch({ true }, perSlice = 3) {
            clock += 10.nanoseconds
        })
        val result = Portfolio.thompson(listOf(worker), baseSliceWork = 1,
            diagnostics = PortfolioDiagnostics(1)).also {
            it.runTimeSource = clock
            it.invocationStarted = clock.markNow()
        }.use {
            it.minimize(Cancellation.Never, onImprovement = { clock += 1.nanoseconds })
        }

        assertEquals(10L, result.stats.portfolio.arms.single().lifecycle.incumbentAvailableNanos)
    }

    @Test
    fun `experimental rewards should not extend an unproductive auxiliary lifetime`() {
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { }),
            trackingWorker("trial", 1, ScriptedSearch({ false }) { }).also { it.improvementOnly = true },
        )
        val result = Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            diagnostics = PortfolioDiagnostics(80), rewardPolicy = PortfolioRewardPolicy { 1.0 }).also {
            it.incumbentProbeArms = setOf(1)
        }.use { it.minimize(Cancellation.Never) }

        assertEquals(12L, result.stats.portfolio.arms[1].work)
    }

    @Test
    fun `idle sharing use should retain its phase credit without charging a segment`() {
        val observed = mutableListOf<PortfolioProgress>()
        val pools = SharedPools(null, null)
        val workers = listOf(
            trackingWorker("producer", 0, ScriptedSearch({ false }) { }),
            trackingWorker("consumer", 1, ScriptedSearch({ it == 0 }) {
                pools.contributions.note(Contribution.Clause, 0)
            }),
        ).onEach { it.sharedPools = pools }
        workers[0].sharing = setOf(Contribution.Clause)

        val result = Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            diagnostics = PortfolioDiagnostics(3),
            rewardPolicy = PortfolioRewardPolicy { observed += it; it.ordinaryReward }).use {
            it.minimize(Cancellation.Never)
        }

        val idle = observed.single { it.idleCredit }
        assertEquals(PortfolioPhase.IMPROVEMENT, idle.phase)
        assertEquals(0L, idle.work)
        assertEquals(1.0, idle.credit["ClauseUses"])
        assertEquals(1.0, result.stats.portfolio.arms[0].phases.sumOf { it.credit["ClauseUses"] ?: 0.0 })
    }

    @Test
    fun `a failed lifecycle observer should still close retained handles`() {
        val search = TrackingResumableSearch(null)
        val worker = trackingWorker("search", 0, search)

        assertFailsWith<IllegalStateException> {
            Portfolio.thompson(listOf(worker), diagnostics = PortfolioDiagnostics(1), onProgress = {
                if (it is PortfolioEvent.Lifecycle && it.kind == PortfolioLifecycleKind.RETIRED) error("sink failed")
            }).use { it.minimize(Cancellation.Never) }
        }

        assertEquals(1, search.closes)
    }

    @Test
    fun `reward experiments should retain silent complete worker coverage`() {
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { }).also { it.family = ArmFamily.LocalSearch },
            trackingWorker("proof", 1, ScriptedSearch({ false }) { }),
        )
        val result = Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(80),
            rewardPolicy = PortfolioRewardPolicy { if (it.arm == 0) 1.0 else 0.0 }).use {
            it.minimize(Cancellation.Never)
        }

        val work = result.stats.portfolio.arms.map { it.work }
        assertTrue(work[1] >= work.sum() / 2 - 2)
    }

    @Test
    fun `accepted progress should distinguish feasibility improvement and closure`() {
        val observations = mutableListOf<PortfolioProgress>()
        var slices = 0
        val base = ScriptedSearch({ it < 2 }) { }
        val search = object : ResumableSearch by base {
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                base.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (++slices == 3) MinimizeResult.Optimal(Sample(BooleanArray(0), LongArray(0)), 998.0)
                else null
            }
        }
        val result = Portfolio.thompson(listOf(trackingWorker("search", 0, search)),
            baseSliceWork = 1, sliceGrowth = 1.0, reseedStaleThreshold = 0,
            diagnostics = PortfolioDiagnostics(10),
            rewardPolicy = PortfolioRewardPolicy { observations += it; it.ordinaryReward }).use {
            it.minimize(Cancellation.Never)
        }

        assertEquals(PortfolioPhase.entries, observations.map { it.phase })
        assertEquals(listOf(1L, 1L, 0L), observations.map { it.acceptedIncumbents })
        assertEquals(1.0, observations.last().credit["ProofClosure"])
        assertEquals(2L, result.stats.portfolio.arms.single().phases.sumOf { it.incumbents })
    }

    @Test
    fun `refuted witness checking should cost work without accepted progress or reward`() {
        val events = mutableListOf<PortfolioEvent>()
        val worker = trackingWorker("search", 0, ScriptedSearch({ true }) { })
        val result = Portfolio(listOf(worker), fixedBandit(1, 0),
            witnessCheck = WitnessCheck { _, _ -> "invalid fixture witness" },
            diagnostics = PortfolioDiagnostics(10), onProgress = events::add,
            rewardPolicy = PortfolioRewardPolicy { error("refuted claims must not earn rewards") }).use {
            it.minimize(Cancellation.Never)
        }

        val progress = events.filterIsInstance<PortfolioEvent.Progressed>().single().progress
        assertEquals(1L, progress.checks)
        assertEquals(0L, progress.acceptedIncumbents)
        assertTrue(progress.refuted)
        assertEquals(0.0, result.stats.portfolio.arms.single().meanReward)
        assertEquals("source-fault", events.filterIsInstance<PortfolioEvent.WorkerExit>().single().cause)
    }

    @Test
    fun `deferred queries should require an accepted global improvement`() {
        for ((improves, rejected, perSlice) in listOf(
            Triple(false, false, 1), Triple(true, false, 1), Triple(true, true, 1), Triple(true, false, 2),
        )) {
            var accepted = 0
            var queries = 0
            val secondProbe = improves && perSlice == 1
            val workers = listOf(
                trackingWorker("finder", 0,
                    ScriptedSearch({ it == 0 || (secondProbe && it == 1) }, perSlice = perSlice) { }),
                trackingWorker("local", 1, ScriptedSearch({ true }, start = 2_000.0) { }),
                trackingWorker("deferred", 2, ScriptedSearch({ false }) {
                    assertTrue(accepted >= 2)
                    queries++
                }).also { it.startAfterAcceptedImprovement = true },
            )
            Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
                reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(24),
                witnessCheck = WitnessCheck { _, value ->
                    if (rejected && value == 998.0) "rejected improvement" else null
                }).use { it.minimize(Cancellation.Never) { accepted++ } }

            assertEquals(improves && !rejected, queries > 0)
        }
    }

    @Test
    fun `a deferred worker should first query after incumbent availability`() {
        val events = mutableListOf<PortfolioEvent>()
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { }),
            trackingWorker("deferred", 1, ScriptedSearch({ false }) { }).also { it.startAfterIncumbent = true },
        )
        Portfolio.thompson(workers, baseSliceWork = 1, sliceGrowth = 1.0,
            diagnostics = PortfolioDiagnostics(10), onProgress = events::add).use {
            it.minimize(Cancellation.Never)
        }

        val life = events.filterIsInstance<PortfolioEvent.Lifecycle>().filter { it.arm == 1 }
        val available = life.indexOfFirst { it.kind == PortfolioLifecycleKind.INCUMBENT_AVAILABLE }
        val queried = life.indexOfFirst { it.kind == PortfolioLifecycleKind.FIRST_QUERY }
        assertTrue(available >= 0 && queried > available)
        assertTrue(life.last().kind == PortfolioLifecycleKind.RETIRED)
    }

    @Test
    fun `preexisting incumbent versions should require fresh accepted progress`() {
        for (improves in listOf(false, true)) {
            val incumbents = PortfolioIncumbents.floating()
            val point = Sample(BooleanArray(0), LongArray(0))
            for (value in listOf(1_000.0, 999.0, 998.0)) incumbents.exchange.offer(point, value)
            var accepted = 0
            var queries = 0
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 },
                    start = if (improves) 998.0 else 1_000.0) { }),
                trackingWorker("deferred", 1, ScriptedSearch({ false }) {
                    assertEquals(1, accepted)
                    queries++
                }).also { it.startAfterAcceptedImprovement = true },
            )

            Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
                reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(12)).use {
                it.minimize(Cancellation.Never, { accepted++ }, incumbents)
            }

            assertEquals(improves, queries > 0)
        }
    }

    @Test
    fun `ties and unpublished objectives should not open progress deferred workers`() {
        for (unpublished in listOf(false, true)) {
            val base = ScriptedSearch({ true }) { }
            var offers = 0
            var queries = 0
            val search = object : ResumableSearch by base {
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
                    base.runSlice(global, sliceMillis, sliceNodes) {
                        val value = if (offers++ > 0 && unpublished) Double.NaN else 999.0
                        onIncumbent(MinimizeResult.BestFound(it.sample, value, TerminationReason.BudgetExhausted))
                    }
            }
            val workers = listOf(
                trackingWorker("finder", 0, search),
                trackingWorker("deferred", 1, ScriptedSearch({ false }) { queries++ })
                    .also { it.startAfterAcceptedImprovement = true },
            )

            Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
                reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(12)).use {
                it.minimize(Cancellation.Never)
            }

            assertTrue(offers > 1)
            assertEquals(0, queries)
        }
    }

    @Test
    fun `cancellation after accepted progress should prevent deferred queries`() {
        var accepted = 0
        var queries = 0
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ true }) { }),
            trackingWorker("deferred", 1, ScriptedSearch({ false }) { queries++ })
                .also { it.startAfterAcceptedImprovement = true },
        )

        Portfolio.thompson(workers, baseSliceWork = 1, maxSliceWork = 1, sliceGrowth = 1.0,
            reseedStaleThreshold = 0, diagnostics = PortfolioDiagnostics(12)).use {
            it.minimize(Cancellation { accepted == 2 }) { accepted++ }
        }

        assertEquals(2, accepted)
        assertEquals(0, queries)
    }

    @Test
    fun `reward experiments should reject nonfinite scores`() {
        val worker = trackingWorker("search", 0, ScriptedSearch({ false }) { })

        assertFailsWith<IllegalArgumentException> {
            Portfolio.thompson(listOf(worker), diagnostics = PortfolioDiagnostics(1),
                rewardPolicy = PortfolioRewardPolicy { Double.NaN }).use { it.minimize(Cancellation.Never) }
        }
    }
}
