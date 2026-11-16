package com.eignex.klause.portfolio

import com.eignex.klause.solver.IncumbentBootstrapSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.ownSearch
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PortfolioPreparationSchedulingTest {
    @Test
    fun `ALNS deferral should retain every LS probe and require one independent sample`() {
        val clock = TestTimeSource()
        val visits = mutableListOf<Int>()
        val sampleWork = LongArray(4)
        var targetStart: List<Long>? = null
        val workers = List(4) { arm ->
            val search = ScriptedSearch({ arm == 1 && it == 0 }) {
                visits += arm
                clock += 1.milliseconds
                if (arm == 1) sampleWork[arm] += 3L
                if (arm == 2) sampleWork[arm] += if (sampleWork[arm] == 0L) 2L else 3L
                if (arm == 3) targetStart = listOf(sampleWork[1], sampleWork[2])
            }
            val observed = if (arm in 1..2) object : ResumableSearch by search {
                override val work: Long get() = sampleWork[arm]
            } else search
            trackingWorker("worker$arm", arm, observed).also {
                if (arm > 0) it.family = if (arm == 3) ArmFamily.Lns else ArmFamily.LocalSearch
                if (arm == 3) it.improvementOnly = true
            }
        }

        val result = Portfolio(workers, fixedBandit(4, 2), baseSliceWork = 5L, maxSliceWork = 5L,
            sliceGrowth = 1.0, reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
            it.initialHeuristicArms = setOf(1, 2)
            it.initialHeuristicTargets = setOf(3)
        }.use { it.minimize(ExecutionControl { targetStart != null || visits.size >= 128 }) }

        assertEquals(listOf(3L, 5L), targetStart)
        val completed = assertNotNull(result.stats.portfolio.heuristicStarts.single().completionNanos)
        assertTrue(assertNotNull(result.stats.portfolio.arms[3].lifecycle.firstQueryNanos) >= completed)
    }

    @Test
    fun `ALNS deferral should retain silent complete worker coverage with all LS probes`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(5)
        val sampleWork = LongArray(5)
        val workers = List(5) { arm ->
            val search = ScriptedSearch({ arm == 2 && it == 0 }) {
                elapsed[arm] += 100L
                clock += 100.milliseconds
                if (arm in 2..3) sampleWork[arm]++
            }
            val observed = if (arm in 2..3) object : ResumableSearch by search {
                override val work: Long get() = sampleWork[arm]
            } else search
            trackingWorker("worker$arm", arm, observed).also {
                if (arm >= 2) it.family = if (arm == 4) ArmFamily.Lns else ArmFamily.LocalSearch
                if (arm == 4) it.improvementOnly = true
            }
        }

        Portfolio(workers, fixedBandit(5, 2), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
            it.initialHeuristicArms = setOf(2, 3)
            it.initialHeuristicTargets = setOf(4)
        }.use { it.minimize(ExecutionControl { elapsed.sum() >= 8_000L }) }

        val owed = (elapsed.sum() - 300L) / 4
        for (arm in 0..1) assertTrue(elapsed[arm] >= owed - 100L)
        for (arm in 2..3) assertTrue(elapsed[arm] > 0L)
        assertEquals(0L, elapsed[4])
    }

    @Test
    fun `ALNS should retain ordinary eligibility when its last sample provider retires`() {
        var providerWork = 0L
        var targetSlices = 0
        var slices = 0
        val provider = ScriptedSearch({ false }) {
            slices++
            providerWork += 3L
            error("provider failed")
        }
        val workers = listOf(
            trackingWorker("complete", 0, ScriptedSearch({ false }) { slices++ }),
            trackingWorker("provider", 1, object : ResumableSearch by provider {
                override val work: Long get() = providerWork
            }).also { it.family = ArmFamily.LocalSearch },
            trackingWorker("target", 2, ScriptedSearch({ false }) {
                slices++
                targetSlices++
            }).also { it.family = ArmFamily.Lns },
        )

        val result = Portfolio(workers, fixedBandit(3, 0), baseSliceWork = 5L, maxSliceWork = 5L,
            reseedStaleThreshold = 0).also {
            it.initialHeuristicArms = setOf(1)
            it.initialHeuristicTargets = setOf(2)
        }.use { it.minimize(ExecutionControl { targetSlices > 0 || slices >= 128 }) }

        assertEquals(1, targetSlices)
        assertFalse(result.stats.portfolio.heuristicStarts.single().completed)
    }

    @Test
    fun `incumbent gated LNS should wait for the same worker heuristic sample`() {
        val clock = TestTimeSource()
        val visits = mutableListOf<Int>()
        var leaderWork = 0L
        var lnsStartWork: Long? = null
        val workers = List(5) { arm ->
            val search = ScriptedSearch({ arm == 2 && it == 0 }) {
                visits += arm
                clock += 1.milliseconds
                if (arm == 2) leaderWork += 2L
                if (arm == 3) lnsStartWork = leaderWork
            }
            val observed = if (arm == 2) object : ResumableSearch by search {
                override val work: Long get() = leaderWork
            } else search
            trackingWorker("worker$arm", arm, observed).also {
                if (arm >= 2) it.family = if (arm == 3) ArmFamily.Lns else ArmFamily.LocalSearch
                if (arm == 3) it.improvementOnly = true
            }
        }

        val result = Portfolio(workers, fixedBandit(5, 2), baseSliceWork = 5L, maxSliceWork = 5L,
            sliceGrowth = 1.0, reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
            it.initialHeuristicArms = setOf(2, 3, 4)
        }.use { it.minimize(ExecutionControl { lnsStartWork != null || visits.size >= 128 }) }

        assertEquals(6L, lnsStartWork)
        assertTrue(result.stats.portfolio.heuristicStarts.single().completed)
        assertTrue(0 in visits && 1 in visits)
    }

    @Test
    fun `deferring LNS after an incumbent should retain silent complete worker coverage`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(5)
        var leaderWork = 0L
        val workers = List(5) { arm ->
            val search = ScriptedSearch({ arm == 2 && it == 0 }) {
                elapsed[arm] += 100L
                clock += 100.milliseconds
                if (arm == 2) leaderWork++
            }
            val observed = if (arm == 2) object : ResumableSearch by search {
                override val work: Long get() = leaderWork
            } else search
            trackingWorker("worker$arm", arm, observed).also {
                if (arm >= 2) it.family = if (arm == 3) ArmFamily.Lns else ArmFamily.LocalSearch
                if (arm == 3) it.improvementOnly = true
            }
        }

        val result = Portfolio(workers, fixedBandit(5, 2), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
            it.initialHeuristicArms = setOf(2, 3, 4)
        }.use { it.minimize(ExecutionControl { elapsed.sum() >= 8_000L }) }

        val owed = (elapsed.sum() - 300L) / 4
        for (arm in 0..1) assertTrue(elapsed[arm] >= owed - 100L)
        assertEquals(0L, elapsed[3])
        assertEquals(0L, elapsed[4])
        assertFalse(result.stats.portfolio.heuristicStarts.single().completed)
    }

    @Test
    fun `paid heuristic admission should publish the checked boundary point before a sibling starts`() {
        val clock = TestTimeSource()
        val point = Sample(BooleanArray(0), LongArray(0))
        var checks = 0
        val exchange = IncumbentExchange<Sample, Double>({ a, b -> a < b }, { candidate ->
            checks++
            clock += 1.milliseconds
            Verification.Accepted(candidate)
        })
        var leaderWork = 0L
        var leaderSlices = 0
        var completeSlices = 0
        var totalSlices = 0
        var siblingStartWork: Long? = null
        val scripted = ScriptedSearch({ false }) {
            totalSlices++
            clock += 1.milliseconds
            leaderWork += listOf(0L, 2L, 3L).getOrElse(leaderSlices++) { 0L }
            exchange.offerFromWorker(point, 11.0 - leaderSlices) { it() }
            exchange.offerFromWorker(point, 10.0 - leaderSlices) { it() }
        }
        val leader = trackingWorker("leader", 1, object : ResumableSearch by scripted {
            override val work: Long get() = leaderWork
        }).also {
            it.family = ArmFamily.LocalSearch
            it.publicationExchange = exchange
            it.sharingMeter = SharingMeter()
        }
        val workers = listOf(
            trackingWorker("complete", 0, ScriptedSearch({ false }) {
                totalSlices++
                completeSlices++
                clock += 1.milliseconds
            }),
            leader,
            trackingWorker("sibling", 2, ScriptedSearch({ false }) {
                totalSlices++
                clock += 1.milliseconds
                siblingStartWork = leaderWork
                assertEquals(7.0, exchange.current()?.objective)
            }).also { it.family = ArmFamily.LocalSearch },
        )

        val result = Portfolio(workers, fixedBandit(3, 1), baseSliceWork = 5L, maxSliceWork = 5L,
            sliceGrowth = 1.0, reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
            it.initialHeuristicArms = setOf(1, 2)
        }.use { it.minimize(ExecutionControl { siblingStartWork != null || totalSlices >= 128 }) }

        val sample = result.stats.portfolio.heuristicStarts.single()
        assertEquals(5L, siblingStartWork)
        assertTrue(sample.completed)
        assertTrue(completeSlices > 0)
        assertEquals(3, checks)
        val offers = result.stats.portfolio.arms[1].sharing.incumbents
        assertEquals(6L, offers.getValue("offered"))
        assertEquals(3L, offers.getValue("coalesced"))
        assertEquals(3L, offers.getValue("checked"))
    }

    @Test
    fun `additional heuristic starts should wait for a complete work sample`() {
        val visits = mutableListOf<Int>()
        var leaderWork = 0L
        var leaderSlices = 0
        var siblingStartWork: Long? = null
        val leaderSearch = ScriptedSearch({ false }) {
            visits += 2
            leaderWork += listOf(0L, 2L, 3L).getOrElse(leaderSlices++) { 0L }
        }
        val leader = object : ResumableSearch by leaderSearch {
            override val work: Long get() = leaderWork
        }
        val workers = List(4) { arm ->
            val search = if (arm == 2) leader else ScriptedSearch({ false }) {
                visits += arm
                if (arm == 3) siblingStartWork = leaderWork
            }
            trackingWorker("worker$arm", arm, search).also { if (arm >= 2) it.family = ArmFamily.LocalSearch }
        }

        Portfolio(workers, fixedBandit(4, 2), baseSliceWork = 5L, maxSliceWork = 5L,
            sliceGrowth = 1.0, reseedStaleThreshold = 0).also {
            it.initialHeuristicArms = setOf(2, 3)
        }.use { it.minimize(ExecutionControl { siblingStartWork != null || visits.size >= 128 }) }

        assertEquals(5L, siblingStartWork)
        assertEquals(3, leaderSlices)
    }

    @Test
    fun `cancellation should retain an incomplete heuristic sample`() {
        var leaderWork = 0L
        var siblingSlices = 0
        val leaderSearch = ScriptedSearch({ false }) { leaderWork++ }
        val leader = object : ResumableSearch by leaderSearch {
            override val work: Long get() = leaderWork
        }
        val workers = listOf(
            trackingWorker("first", 0, leader),
            trackingWorker("second", 1, ScriptedSearch({ false }) { siblingSlices++ }),
        ).onEach { it.family = ArmFamily.LocalSearch }

        val result = Portfolio(workers, fixedBandit(2, 0), baseSliceWork = 5L, maxSliceWork = 5L,
            reseedStaleThreshold = 0).also {
            it.initialHeuristicArms = setOf(0, 1)
        }.use { it.minimize(ExecutionControl { leaderWork >= 2L }) }

        val sample = result.stats.portfolio.heuristicStarts.single()
        assertFalse(sample.completed)
        assertEquals(mapOf("first" to 2L, "second" to 0L), sample.workByArm)
        assertEquals(0, siblingSlices)
    }

    @Test
    fun `failed heuristic work should not complete another worker sample`() {
        var failedWork = 0L
        var nextWork = 0L
        var thirdStartWork: Long? = null
        var slices = 0
        val failedSearch = ScriptedSearch({ false }) {
            slices++
            failedWork += 3L
            error("startup failed")
        }
        val failed = object : ResumableSearch by failedSearch {
            override val work: Long get() = failedWork
        }
        val nextSearch = ScriptedSearch({ false }) {
            slices++
            nextWork += 2L
        }
        val next = object : ResumableSearch by nextSearch {
            override val work: Long get() = nextWork
        }
        val workers = listOf(failed, next, ScriptedSearch({ false }) {
            slices++
            thirdStartWork = nextWork
        })
            .mapIndexed { arm, search ->
                trackingWorker("worker$arm", arm, search).also { it.family = ArmFamily.LocalSearch }
            }

        val result = Portfolio(workers, fixedBandit(3, 1), baseSliceWork = 5L, maxSliceWork = 5L,
            reseedStaleThreshold = 0).also {
            it.initialHeuristicArms = setOf(0, 1, 2)
        }.use { it.minimize(ExecutionControl { thirdStartWork != null || slices >= 128 }) }

        val sample = result.stats.portfolio.heuristicStarts.single()
        assertEquals(6L, thirdStartWork)
        assertEquals(3L, sample.workByArm["worker0"])
        assertTrue(sample.completed)
    }

    @Test
    fun `deferring heuristic starts should retain silent complete worker coverage`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(4)
        var leaderWork = 0L
        val workers = List(4) { arm ->
            val search = ScriptedSearch({ arm == 2 && it == 0 }) {
                elapsed[arm] += 100L
                clock += 100.milliseconds
                if (arm == 2) leaderWork++
            }
            val observed = if (arm == 2) object : ResumableSearch by search {
                override val work: Long get() = leaderWork
            } else search
            trackingWorker("worker$arm", arm, observed).also { if (arm >= 2) it.family = ArmFamily.LocalSearch }
        }

        Portfolio(workers, fixedBandit(4, 2), baseSliceWork = 5L, maxSliceWork = 5L,
            reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
            it.initialHeuristicArms = setOf(2, 3)
        }.use { it.minimize(ExecutionControl { elapsed.sum() >= 8_000L }) }

        val owed = (elapsed.sum() - 300L) / 4
        for (arm in 0..1) assertTrue(elapsed[arm] >= owed - 100L)
    }

    @Test
    fun `first witness workers should retire after either sibling supplies an incumbent`() {
        for (helperFindsFirst in listOf(false, true)) {
            var helperSlices = 0
            var siblingSlices = 0
            val point = MinimizeResult.BestFound(Sample(BooleanArray(0), LongArray(0)), 0.0,
                TerminationReason.BudgetExhausted)
            val helper = TrackingResumableSearch(null, point.takeIf { helperFindsFirst },
                onRun = { helperSlices++ })
            val sibling = TrackingResumableSearch(null, point, onRun = { siblingSlices++ })
            val workers = listOf(
                trackingWorker("helper", 0, helper).also { it.firstWitnessOnly = true },
                trackingWorker("sibling", 1, sibling),
            )

            val result = Portfolio.thompson(workers).use {
                it.minimize(ExecutionControl { siblingSlices >= 3 })
            }

            assertIs<MinimizeResult.BestFound>(result)
            assertEquals(1, helperSlices)
            assertEquals(3, siblingSlices)
            assertEquals(1, helper.closes)
            assertEquals(1, sibling.closes)
        }
    }

    @Test
    fun `preparation revisits should follow every initial sibling admission`() {
        for (instructions in listOf(false, true)) {
            val order = mutableListOf<Int>()
            val prepared = TrackingResumableSearch(
                null,
                onRun = { order += 0 },
                preparationPending = !instructions,
            )
            val worker = PortfolioWorker.ofMinimize(
                "prepared",
                0,
                countsInstructions = instructions,
                resumable = { prepared },
            ) { _, _, _, _ ->
                error("the retained handle must resume")
            }
            val workers = listOf(
                worker,
                trackingWorker("sibling1", 1, TrackingResumableSearch(null, onRun = { order += 1 })),
                trackingWorker("sibling2", 2, TrackingResumableSearch(null, onRun = { order += 2 })),
            )

            Portfolio.thompson(workers).use { it.minimize(ExecutionControl { order.size >= 4 }) }

            assertEquals(listOf(0, 1, 2, 0), order)
        }
    }

    @Test
    fun `only pending bootstrap should resume before the first incumbent after sibling admission`() {
        for (bootstrapSlices in listOf(0, 1, 2)) {
            val order = mutableListOf<Int>()
            var opened = 0
            var slices = 0
            val tracked = TrackingResumableSearch(null)
            val handle = object : IncumbentBootstrapSearch, ResumableSearch by tracked {
                override val bootstrapPending: Boolean get() = slices < bootstrapSlices
                override fun runSlice(
                    global: ExecutionControl,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    order += 0
                    if (++slices == 2) {
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
            }
            val workers = listOf(
                PortfolioWorker.ofMinimize("bootstrap", 0, resumable = {
                    opened++
                    handle
                }) { _, _, _, _ ->
                    error("the retained handle must resume")
                }.also { it.family = ArmFamily.Lns },
            ) + List(2) { sibling ->
                val arm = sibling + 1
                val search = TrackingResumableSearch(null, onRun = { order += arm })
                val siblingHandle = object : ResumableSearch by search {
                    override val stats = SolveStats(ls = LocalSearchStats(moves = SumResult(1.0)))
                }
                trackingWorker("sibling$arm", arm, siblingHandle).also { it.family = ArmFamily.LocalSearch }
            }
            val bandit = fixedBandit(workers.size, arm = 0)

            val result = Portfolio(workers, bandit).use { it.minimize(ExecutionControl { order.size >= 4 }) }

            assertEquals(listOf(0, 1, 2, if (bootstrapSlices == 2) 0 else 1), order)
            assertEquals(bootstrapSlices == 2, result is MinimizeResult.BestFound)
            assertEquals(1, opened)
            assertEquals(1, tracked.closes)
        }
    }

    @Test
    fun `unfinished preparation should rotate after every initial sibling admission`() {
        val order = mutableListOf<Int>()
        val workers = List(3) { arm ->
            val handle = TrackingResumableSearch(null, onRun = { order += arm })
            PortfolioWorker.ofMinimize(
                "preparing$arm",
                arm,
                countsInstructions = true,
                resumable = { handle },
            ) { _, _, _, _ ->
                error("the retained handle must resume")
            }
        }

        Portfolio.thompson(workers).use { it.minimize(ExecutionControl { order.size >= 9 }) }

        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1, 2), order)
    }

    @Test
    fun `bounded preparation should retain pending search across plateau slices`() {
        for (threshold in listOf(1, 3)) {
            var slices = 0
            val worker = PortfolioWorker.ofMinimize(
                "preparing",
                0,
                resumable = {
                    val source = ScriptedSearch({ it == 0 || it == 5 }) { slices++ }
                    ownSearch(object : ResumableSearch by source, ReseedGuard {
                        override val canReseed: Boolean get() = slices >= 6
                    }) {}
                },
            ) { _, _, _, _ -> error("the retained handle must resume") }
                .also { it.restartingSearch = true }

            val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = threshold).use {
                it.minimize(ExecutionControl { slices >= 6 })
            }

            assertEquals(998.0, assertIs<MinimizeResult.BestFound>(result).objectiveValue)
        }
    }

    @Test
    fun `productive bounded preparation should finish before returning to the bandit`() {
        val slices = IntArray(2)
        val source = ScriptedSearch({ it == 0 || it == 5 }) { slices[0]++ }
        val preparing = ownSearch(object : ResumableSearch by source, ReseedGuard {
            override val canReseed: Boolean get() = slices[0] >= 6
            override val canContinuePreparation: Boolean get() = slices[0] < 6
        }) {}
        val workers = listOf(
            trackingWorker("preparing", 0, preparing),
            trackingWorker("preferred", 1, ScriptedSearch({ false }) { slices[1]++ }),
        )
        val bandit = fixedBandit(2, arm = 1)

        val result = Portfolio(workers, bandit).also {
            it.runTimeSource = TestTimeSource()
        }.use {
            it.minimize(ExecutionControl { slices.sum() >= 8 })
        }

        assertEquals(998.0, assertIs<MinimizeResult.BestFound>(result).objectiveValue)
        assertEquals(listOf(6, 2), slices.toList())
    }

    @Test
    fun `minimum shares should precede productive preparation continuation`() {
        val slices = IntArray(2)
        val source = ScriptedSearch({ it == 0 || it == 5 }) { slices[0]++ }
        val preparing = ownSearch(object : ResumableSearch by source, ReseedGuard {
            override val canReseed: Boolean get() = false
            override val canContinuePreparation: Boolean get() = true
        }) {}
        val workers = listOf(
            trackingWorker("preparing", 0, preparing),
            trackingWorker("reserved", 1, ScriptedSearch({ false }) { slices[1]++ }),
        )

        val result = Portfolio.thompson(workers, minShares = doubleArrayOf(0.0, 1.0)).use {
            it.minimize(ExecutionControl { slices.sum() >= 8 })
        }

        assertEquals(999.0, assertIs<MinimizeResult.BestFound>(result).objectiveValue)
        assertEquals(listOf(1, 7), slices.toList())
    }
}
