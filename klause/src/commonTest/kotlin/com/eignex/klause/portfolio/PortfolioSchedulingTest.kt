package com.eignex.klause.portfolio

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.IncumbentBootstrapSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.ownSearch
import com.eignex.klause.solver.result.LocalSearchStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class PortfolioSchedulingTest {
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
                it.minimize(Cancellation { siblingSlices >= 3 })
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

            Portfolio.thompson(workers).use { it.minimize(Cancellation { order.size >= 4 }) }

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
                    global: Cancellation,
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

            val result = Portfolio(workers, bandit).use { it.minimize(Cancellation { order.size >= 4 }) }

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

        Portfolio.thompson(workers).use { it.minimize(Cancellation { order.size >= 9 }) }

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
                it.minimize(Cancellation { slices >= 6 })
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
            it.minimize(Cancellation { slices.sum() >= 8 })
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
            it.minimize(Cancellation { slices.sum() >= 8 })
        }

        assertEquals(999.0, assertIs<MinimizeResult.BestFound>(result).objectiveValue)
        assertEquals(listOf(1, 7), slices.toList())
    }

    @Test
    fun `non-restarting complete search should reach improvements beyond non-improving segments`() {
        for (threshold in listOf(1, 3)) {
            var slices = 0
            val worker = PortfolioWorker.ofMinimize(
                "complete",
                0,
                resumable = { ScriptedSearch({ it == 0 || it == 5 }) { slices++ } },
            ) { _, _, _, _ -> error("the retained handle must resume") }

            val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = threshold).use {
                it.minimize(Cancellation { slices >= 10 })
            }

            assertEquals(998.0, assertIs<MinimizeResult.BestFound>(result).objectiveValue)
        }
    }

    @Test
    fun `non-restarting complete search should retain its handle after an incumbent tightens`() {
        for (threshold in listOf(1, 3)) {
            var slices = 0
            val worker = PortfolioWorker.ofMinimize(
                "complete",
                0,
                resumable = { ScriptedSearch({ it == 0 || it == 5 }) { slices++ } },
            ) { _, _, _, _ -> error("the retained handle must resume") }

            val result = Portfolio.thompson(listOf(worker), reseedStaleThreshold = threshold).use {
                it.minimize(Cancellation { slices >= 13 })
            }

            assertEquals(0L, result.stats.portfolio.arms.single().reseeds)
        }
    }

    @Test
    fun `useless arms should take a small share of the run however many there are`() {
        for (useless in listOf(1, 4, 12)) {
            val slices = IntArray(useless + 1)
            var totalSlices = 0
            val workers = List(useless + 1) { arm ->
                trackingWorker("arm$arm", arm, ScriptedSearch({ arm == 0 }) {
                    slices[arm]++
                    totalSlices++
                })
            }
            Portfolio.thompson(workers).also { it.runTimeSource = TestTimeSource() }.use {
                it.minimize(Cancellation { totalSlices >= 512 })
            }

            assertEquals(512, slices.sum())
            val share = slices.drop(1).sum().toDouble() / slices.sum()
            assertTrue(share < 0.05, "$useless useless arms took $share of the slices")
        }
    }

    @Test
    fun `plateaued local search should cede the run to backtrack however many local search arms there are`() {
        val slices = IntArray(5)
        val workers = List(5) { arm ->
            trackingWorker("arm$arm", arm, ScriptedSearch({ arm == 0 && it == 0 }) { slices[arm]++ })
                .also { if (arm > 0) it.family = ArmFamily.LocalSearch }
        }
        var polls = 0

        Portfolio.thompson(workers).use { it.minimize(Cancellation { ++polls > 2_000 }) }

        assertTrue(slices[0] > slices.sum() / 2, "backtrack ran ${slices[0]} of ${slices.sum()} slices")
    }

    @Test
    fun `a silent complete arm should reach its first witness despite a competing bandit preference`() {
        val slices = IntArray(2)
        val workers = listOf(
            trackingWorker("preferred", 0, ScriptedSearch({ false }) { slices[0]++ }),
            trackingWorker("finder", 1, ScriptedSearch({ it == 2 }) { slices[1]++ }),
        )
        val bandit = fixedBandit(2, arm = 0)

        val result = Portfolio(workers, bandit).use {
            it.minimize(Cancellation { slices.sum() >= 8 })
        }

        assertEquals(999.0, assertIs<MinimizeResult.WithSample>(result).objectiveValue)
    }

    @Test
    fun `violation progress followed by stagnation should cede time before an accepted incumbent`() {
        val slices = IntArray(2)
        val source = ScriptedSearch({ false }) { slices[1]++ }
        val heuristic = object : ResumableSearch by source {
            override val stats: SolveStats
                get() = SolveStats(
                    ls = LocalSearchStats(
                        moves = SumResult(slices[1] * 10.0),
                        incumbentViolation = if (slices[1] < 3) 10.0 else 5.0,
                    ),
                )
        }
        val workers = listOf(
            trackingWorker("complete", 0, ScriptedSearch({ false }) { slices[0]++ }),
            PortfolioWorker.ofMinimize("heuristic", 1, countsInstructions = true, resumable = { heuristic }) {
                    _,
                    _,
                    _,
                    _,
                ->
                error("the retained handle must resume")
            },
        )

        val result = Portfolio.thompson(workers, seed = 3L).use {
            it.minimize(Cancellation { slices.sum() >= 200 })
        }

        assertIs<MinimizeResult.Unknown>(result)
        assertTrue(slices[0] > 2 * slices[1], "complete search ran ${slices[0]} of ${slices.sum()} slices")
        assertTrue(result.stats.portfolio.arms[1].credit.getValue("Violation") > 0.0)
        assertFalse(result.stats.portfolio.arms[1].credit.containsKey("FirstSolution"))
    }

    @Test
    fun `cancelled heuristic initialization should preserve feasibility feedback regardless of charged work`() {
        val schedules = listOf(0L, 17L).map { initialWork ->
            val visits = ArrayList<Int>()
            var moves = 0
            val source = ScriptedSearch({ false }) {
                visits += 1
                moves++
            }
            val heuristic = object : ResumableSearch by source {
                override val stats: SolveStats
                    get() = SolveStats(ls = LocalSearchStats(moves = SumResult(moves * 10.0)))
            }
            val workers = listOf(
                trackingWorker("complete", 0, ScriptedSearch({ false }) { visits += 0 }),
                PortfolioWorker.ofMinimize("heuristic", 1, countsInstructions = true, resumable = { heuristic }) {
                        _,
                        _,
                        _,
                        _,
                    ->
                    error("the retained handle must resume")
                },
                PortfolioWorker.ofMinimize("opening", 2, countsInstructions = true, resumable = {
                    visits += 2
                    throw SearchInitializationCancelled(SolveStats.EMPTY, initialWork)
                }) { _, _, _, _ -> error("cancelled initialization must retire") },
            )
            val bandit = object : UnivariateBandit {
                override val nbrArms = workers.size
                override val random = Random(3)
                override fun choose() = 0
                override fun update(armIndex: Int, value: Double, weight: Double) = Unit
                override fun reset() = Unit
            }

            Portfolio(workers, bandit).also { it.runTimeSource = TestTimeSource() }.use {
                it.minimize(Cancellation { visits.size >= 200 })
            }
            visits
        }

        assertEquals(schedules.first(), schedules.last())
    }

    @Test
    fun `complete workers should receive elapsed opportunity despite heterogeneous segment costs`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(3)
        val visits = IntArray(3)
        val costs = longArrayOf(10L, 100L, 500L)
        val workers = List(3) { arm ->
            val source = ScriptedSearch({ false }) {
                visits[arm]++
                elapsed[arm] += costs[arm]
                clock += costs[arm].milliseconds
            }
            val handle = object : ResumableSearch by source {
                override val stats: SolveStats
                    get() = if (arm == 2) {
                        SolveStats(
                            ls = LocalSearchStats(
                                moves = SumResult(visits[arm] * 10.0),
                                incumbentViolation = 1.0 / (visits[arm] + 1),
                            ),
                        )
                    } else {
                        SolveStats.EMPTY
                    }
            }
            if (arm == 2) {
                PortfolioWorker.ofMinimize(
                    "heuristic",
                    arm,
                    countsInstructions = true,
                    resumable = { handle },
                ) { _, _, _, _ -> error("the retained handle must resume") }
            } else {
                trackingWorker("arm$arm", arm, handle)
            }
        }

        val result = Portfolio.thompson(workers, seed = 3L).also { it.runTimeSource = clock }.use {
            it.minimize(Cancellation { elapsed.sum() >= 6_000L })
        }

        assertIs<MinimizeResult.Unknown>(result)
        val owed = (elapsed.sum() - 2_000L) / 4
        assertTrue(elapsed[0] >= owed - costs.max(), "cheap complete worker received ${elapsed[0]} ms, owed $owed")
        assertTrue(elapsed[1] >= owed - costs.max(), "costly complete worker received ${elapsed[1]} ms, owed $owed")
        assertTrue(visits[0] > visits[1])
        assertTrue(elapsed[2] > 0L)
    }

    @Test
    fun `finished complete workers should leave elapsed coverage to their live siblings`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(3)
        val finished = TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted), onRun = {
            elapsed[0] += 250L
            clock += 250.milliseconds
        })
        val workers = listOf(
            trackingWorker("finished", 0, finished),
            trackingWorker(
                "live",
                1,
                ScriptedSearch({ false }) {
                    elapsed[1] += 10L
                    clock += 10.milliseconds
                },
            ),
            trackingWorker(
                "heuristic",
                2,
                ScriptedSearch({ false }) {
                    elapsed[2] += 500L
                    clock += 500.milliseconds
                },
            ).also { it.family = ArmFamily.LocalSearch },
        )

        Portfolio.thompson(workers, seed = 3L).also { it.runTimeSource = clock }.use {
            it.minimize(Cancellation { elapsed.sum() >= 6_000L })
        }

        assertEquals(250L, elapsed[0])
        assertEquals(1, finished.closes)
        assertTrue(elapsed[1] >= (elapsed[1] + elapsed[2] - 2_000L) / 2 - 500L)
        assertTrue(elapsed[2] > 0L)
    }

    @Test
    fun `repeated heuristic preparation should preserve elapsed opportunity for silent complete search`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(2)
        val complete = TrackingResumableSearch(null, onRun = {
            elapsed[0] += 10L
            clock += 10.milliseconds
        })
        val preparing = TrackingResumableSearch(null, onRun = {
            elapsed[1] += 500L
            clock += 500.milliseconds
        }, preparationPending = true)
        val workers = listOf(
            trackingWorker("complete", 0, complete),
            trackingWorker("preparing", 1, preparing).also { it.family = ArmFamily.LocalSearch },
        )

        val result = Portfolio.thompson(workers, seed = 3L).also { it.runTimeSource = clock }.use {
            it.minimize(Cancellation { elapsed.sum() >= 6_000L })
        }

        assertIs<MinimizeResult.Unknown>(result)
        assertTrue(elapsed[0] >= (elapsed.sum() - 2_000L) / 2 - 500L)
        assertEquals(1, complete.closes)
        assertEquals(1, preparing.closes)
        assertEquals(0L, result.stats.portfolio.arms[0].work)
    }

    @Test
    fun `an accepted incumbent should end feasibility elapsed coverage`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(2)
        val workers = listOf(
            trackingWorker(
                "complete",
                0,
                ScriptedSearch({ false }) {
                    elapsed[0] += 10L
                    clock += 10.milliseconds
                },
            ),
            trackingWorker(
                "improver",
                1,
                ScriptedSearch({ true }) {
                    elapsed[1] += 500L
                    clock += 500.milliseconds
                },
            ).also { it.family = ArmFamily.LocalSearch },
        )
        var checks = 0
        val portfolio = Portfolio.thompson(workers, seed = 3L, witnessCheck = { _, _ ->
            checks++
            null
        }).also { it.runTimeSource = clock }

        val result = portfolio.use { it.minimize(Cancellation { elapsed.sum() >= 6_000L }) }

        assertIs<MinimizeResult.BestFound>(result)
        assertTrue(checks > 0)
        assertTrue(elapsed[0] < 500L)
    }

    @Test
    fun `the arm that found the first solution should lose its lead to the arm improving it`() {
        val slices = IntArray(2)
        val finder = ScriptedSearch({ it == 0 }, start = 1_000.0) { slices[0]++ }
        val improver = ScriptedSearch({ it > 0 }, start = 500.0) { slices[1]++ }
        val workers = listOf(trackingWorker("finder", 0, finder), trackingWorker("improver", 1, improver))
        var polls = 0

        Portfolio.thompson(workers, seed = 3L).also { it.runTimeSource = TestTimeSource() }.use {
            it.minimize(Cancellation { ++polls > 100 })
        }

        assertTrue(slices[1] > 3 * slices[0], "finder ${slices[0]} slices, improver ${slices[1]}")
    }

    @Test
    fun `added improvement variants should wait until the first incumbent`() {
        val slices = IntArray(2)
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ false }) { slices[0]++ }),
            trackingWorker("variant", 1, ScriptedSearch({ true }) { slices[1]++ })
                .also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers).use { it.minimize(Cancellation { slices.sum() >= 4 }) }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(listOf(4, 0), slices.toList())
    }

    @Test
    fun `the first incumbent should admit improvement variants at their base allowance`() {
        val slices = IntArray(2)
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 2 }) { slices[0]++ }),
            trackingWorker("variant", 1, ScriptedSearch({ true }, start = 500.0) { slices[1]++ })
                .also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers, baseSliceWork = 7L).use {
            it.minimize(Cancellation { slices[1] >= 1 })
        }

        assertEquals(499.0, assertIs<MinimizeResult.WithSample>(result).objectiveValue)
        assertEquals(7L, result.stats.portfolio.arms[1].work)
        assertEquals(listOf(3, 1), slices.toList())
    }

    @Test
    fun `retiring the first solution pool should admit deferred arms`() {
        val unfinished = TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted))
        val witness = Sample(BooleanArray(0), LongArray(0))
        val deferred = TrackingResumableSearch(MinimizeResult.Optimal(witness, 3.0))
        val workers = listOf(
            trackingWorker("unfinished", 0, unfinished),
            trackingWorker("deferred", 1, deferred).also { it.improvementOnly = true },
        )

        val result = Portfolio.thompson(workers).use { it.minimize() }

        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(result).objectiveValue)
    }

    @Test
    fun `stagnant feasibility search should cede family share regardless of the local search arm count`() {
        for (locals in listOf(1, 4, 8)) {
            val slices = IntArray(locals + 1)
            val workers = List(locals + 1) { arm ->
                PortfolioWorker.ofSolve("arm$arm", arm) { _, _ ->
                    slices[arm]++
                    SolveResult.Unknown(TerminationReason.BudgetExhausted)
                }.also { if (arm > 0) it.family = ArmFamily.LocalSearch }
            }

            Portfolio.thompson(workers, seed = 3L).use { it.solve(Cancellation { slices.sum() >= 1_000 }) }

            val localShare = slices.drop(1).sum().toDouble() / slices.sum()
            assertTrue(localShare < 0.35, "$locals local search arms took $localShare of the segments")
        }
    }

    @Test
    fun `deferred variants should preserve the first solution family schedule`() {
        val schedules = listOf(0, 4, 8).map { variants ->
            val chosen = ArrayList<Int>()
            val workers = List(variants + 2) { arm ->
                PortfolioWorker.ofSolve("arm$arm", arm) { _, _ ->
                    chosen += arm
                    SolveResult.Unknown(TerminationReason.BudgetExhausted)
                }.also {
                    if (arm > 0) it.family = ArmFamily.LocalSearch
                    it.improvementOnly = arm >= 2
                }
            }

            Portfolio.thompson(workers, seed = 3L).use { it.solve(Cancellation { chosen.size >= 100 }) }
            chosen
        }

        assertEquals(schedules.first(), schedules[1])
        assertEquals(schedules.first(), schedules[2])
    }

    @Test
    fun `an arm raising the proven floor should take more of the run than one doing nothing`() {
        val slices = IntArray(3)
        val floor = SharedObjectiveBound()
        floor.publish(0.0)
        val pools = SharedPools(clauses = null, cuts = null, bounds = floor)
        val finder = ScriptedSearch({ it == 0 }) { slices[0]++ }
        val raiser = ScriptedSearch({ false }) {
            slices[1]++
            pools.contributions.note(Contribution.Floor, 1, floor.publish(floor.current() + 1.0))
        }
        val idle = ScriptedSearch({ false }) { slices[2]++ }
        val workers = listOf(finder, raiser, idle).mapIndexed { arm, search ->
            trackingWorker("arm$arm", arm, search).also {
                it.sharedPools = pools
                it.sharing = setOf(Contribution.Floor)
            }
        }
        var polls = 0

        Portfolio.thompson(workers).also { it.runTimeSource = TestTimeSource() }.use {
            it.minimize(Cancellation { ++polls > 300 })
        }

        assertTrue(slices[1] > 3 * slices[2], "raiser ${slices[1]} slices, idle ${slices[2]}")
    }

    @Test
    fun `an arm whose shared clauses others use should take more of the run than one sharing nothing`() {
        val slices = IntArray(3)
        val pools = SharedPools(clauses = null, cuts = null)
        val finder = ScriptedSearch({ true }) {
            slices[0]++
            pools.contributions.note(Contribution.Clause, origin = 1, amount = 5.0)
        }
        val sharer = ScriptedSearch({ false }) { slices[1]++ }
        val idle = ScriptedSearch({ false }) { slices[2]++ }
        val workers = listOf(finder, sharer, idle).mapIndexed { arm, search ->
            trackingWorker("arm$arm", arm, search).also {
                it.sharedPools = pools
                it.sharing = setOf(Contribution.Clause)
            }
        }
        var polls = 0

        Portfolio.thompson(workers).also { it.runTimeSource = TestTimeSource() }.use {
            it.minimize(Cancellation { ++polls > 300 })
        }

        assertTrue(slices[1] > 3 * slices[2], "sharer ${slices[1]} slices, idle ${slices[2]}")
    }

    @Test
    fun `an arm below its owed share should be scheduled before the policy chooses`() {
        val reached = ArrayList<Int>()
        val fallback = TimeSource.Monotonic.markNow() + 10.seconds

        endlessCountedPortfolio(reached, sliceMillis = 20L, minShares = doubleArrayOf(1.0, 0.0)).use {
            it.minimize(Cancellation { reached.size >= 5 || fallback.hasPassedNow() })
        }

        assertEquals(listOf(0, 1, 0, 0, 0), reached)
    }

    @Test
    fun `the first incumbent should release a feasibility only reservation`() {
        for (feasibilityOnly in listOf(false, true)) {
            val clock = TestTimeSource()
            val reached = ArrayList<Int>()
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) {
                    reached += 0
                    clock += 1.milliseconds
                }),
                trackingWorker("improver", 1, TrackingResumableSearch(null, onRun = {
                    reached += 1
                    clock += 1.milliseconds
                })),
            ).onEach { it.restartingSearch = true }
            val bandit = fixedBandit(2, arm = 1)

            Portfolio(workers, bandit, minShares = doubleArrayOf(1.0, 0.0)).also {
                it.runTimeSource = clock
            }.use {
                it.reserveBeforeIncumbentOnly = feasibilityOnly
                it.minimize(Cancellation { reached.size >= 3 })
            }

            assertEquals(listOf(0, 1, if (feasibilityOnly) 1 else 0), reached)
        }
    }

    @Test
    fun `incumbent probes should continue only after earning progress`() {
        for (productive in listOf(false, true)) {
            val reached = ArrayList<Int>()
            val probe = ScriptedSearch({ productive && it == 0 }, start = 500.0) { reached += 1 }
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
                trackingWorker("probe", 1, probe),
            )
            val bandit = fixedBandit(2, arm = 1)

            val result = Portfolio(workers, bandit, maxSliceWork = 5_000L).also {
                it.runTimeSource = TestTimeSource()
            }.use {
                it.incumbentProbeArms = setOf(1)
                it.minimize(Cancellation { reached.size >= 15 })
            }

            assertIs<MinimizeResult.BestFound>(result)
            if (productive) {
                assertEquals(listOf(0) + List(14) { 1 }, reached)
            } else {
                assertEquals(60_000L, probe.work)
                assertEquals(listOf(0, 0), reached.takeLast(2))
            }
        }
    }

    @Test
    fun `incumbent probes should retain feasibility work before a witness`() {
        val slices = IntArray(2)
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ false }) { slices[0]++ }),
            trackingWorker("probe", 1, ScriptedSearch({ it == 2 }) { slices[1]++ }),
        )

        val result = Portfolio.thompson(workers, minShares = doubleArrayOf(0.0, 1.0)).use {
            it.incumbentProbeArms = setOf(1)
            it.minimize(Cancellation { slices.sum() >= 5 })
        }

        assertIs<MinimizeResult.BestFound>(result)
        assertEquals(4, slices[1])
    }
}
