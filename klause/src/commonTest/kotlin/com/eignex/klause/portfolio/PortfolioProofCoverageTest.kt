package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PortfolioProofCoverageTest {
    @Test
    fun `configured proof share should cover silent retained siblings in elapsed and work schedules`() {
        for (share in listOf(0.5, 0.75, 1.0)) {
            for (workDriven in listOf(false, true)) {
                val clock = TestTimeSource()
                val elapsed = LongArray(3)
                val workers = List(3) { arm ->
                    trackingWorker("worker$arm", arm, ScriptedSearch({ arm == 0 && it == 0 }) {
                        elapsed[arm] += 100L
                        clock += 100.milliseconds
                    }).also { it.restartingSearch = arm == 0 }
                }

                Portfolio(workers, fixedBandit(3, 0), reseedStaleThreshold = 0,
                    baseSliceWork = 1, maxSliceWork = 1,
                    diagnostics = if (workDriven) PortfolioDiagnostics(64) else null).also {
                    it.runTimeSource = clock
                    it.improvementProofShare = share
                }.use { it.minimize(Cancellation { elapsed.sum() >= 6_400L }) }

                val owed = (elapsed.sum() - 100L) * share / 2
                for (arm in 1..2) {
                    assertTrue(elapsed[arm] >= owed - 100L,
                        "share=$share work=$workDriven arm=$arm received=${elapsed[arm]} owed=$owed")
                }
                assertTrue(kotlin.math.abs(elapsed[1] - elapsed[2]) <= 100L)
            }
        }
    }

    @Test
    fun `silent retained traversal should prove before long rewarded segments consume the remaining time`() {
        val clock = TestTimeSource()
        val visits = IntArray(2)
        var elapsed = 0L
        val finderBase = ScriptedSearch({ it == 0 }) {
            val cost = if (visits[0] == 0) 500L else 1_500L
            visits[0]++
            elapsed += cost
            clock += cost.milliseconds
        }
        val finder = object : ResumableSearch by finderBase {
            override val stats: SolveStats
                get() = SolveStats(search = SearchStats(glueClauses = SumResult(visits[0].toDouble())))
        }
        val proofBase = ScriptedSearch({ false }) {
            visits[1]++
            elapsed += 100L
            clock += 100.milliseconds
        }
        val proof = object : ResumableSearch by proofBase {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (visits[1] == 6) {
                    MinimizeResult.Optimal(Sample(BooleanArray(0), LongArray(0)), 999.0)
                } else null
            }
        }
        val workers = listOf(
            trackingWorker("finder", 0, finder).also { it.restartingSearch = true },
            trackingWorker("retained", 1, proof),
        )

        val result = Portfolio(workers, fixedBandit(2, 0), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
        }.use { it.minimize(Cancellation { elapsed >= 3_000L }) }

        assertIs<MinimizeResult.Optimal>(result)
        assertTrue(result.stats.portfolio.arms[0].meanReward > result.stats.portfolio.arms[1].meanReward)
        assertEquals(0.0, result.stats.portfolio.arms[1].meanReward)
    }

    @Test
    fun `retained proof workers should share elapsed coverage despite unequal segment costs`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(3)
        val costs = longArrayOf(250L, 10L, 40L)
        val workers = List(3) { arm ->
            trackingWorker("worker$arm", arm, ScriptedSearch({ arm == 0 && it == 0 }) {
                elapsed[arm] += costs[arm]
                clock += costs[arm].milliseconds
            }).also { it.restartingSearch = arm == 0 }
        }

        Portfolio(workers, fixedBandit(3, 0), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
        }.use { it.minimize(Cancellation { elapsed.sum() >= 8_000L }) }

        val owed = (elapsed.sum() - costs[0]) / 4
        assertTrue(elapsed[1] >= owed - costs.max(), "cheap proof worker received ${elapsed[1]} ms, owed $owed")
        assertTrue(elapsed[2] >= owed - costs.max(), "costly proof worker received ${elapsed[2]} ms, owed $owed")
        assertTrue(elapsed[0] >= (elapsed.sum() - costs[0]) / 2 - costs.max())
    }

    @Test
    fun `accepted improvements should reset dormant proof coverage`() {
        val clock = TestTimeSource()
        val visits = IntArray(2)
        val elapsed = LongArray(2)
        val workers = List(2) { arm ->
            trackingWorker("worker$arm", arm, ScriptedSearch({ arm == 0 }) {
                visits[arm]++
                elapsed[arm] += 500L
                clock += 500.milliseconds
            }).also { it.restartingSearch = arm == 0 }
        }

        Portfolio(workers, fixedBandit(2, 0), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
        }.use { it.minimize(Cancellation { elapsed.sum() >= 8_000L }) }

        assertEquals(1, visits[1])
    }

    @Test
    fun `retained proof coverage should follow every initial sibling admission`() {
        val clock = TestTimeSource()
        val visits = mutableListOf<Int>()
        var elapsed = 0L
        val workers = List(3) { arm ->
            trackingWorker("worker$arm", arm, ScriptedSearch({ arm == 0 && it == 0 }) {
                visits += arm
                elapsed += 100L
                clock += 100.milliseconds
            }).also { it.restartingSearch = arm != 1 }
        }

        Portfolio(workers, fixedBandit(3, 0), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
        }.use { it.minimize(Cancellation { elapsed >= 600L }) }

        assertEquals(listOf(0, 1, 2), visits.take(3))
        assertTrue(visits.drop(3).contains(1))
    }

    @Test
    fun `workers without retained exhaustive traversal should not receive proof coverage`() {
        for (role in listOf("restarting", "improvement", "witness")) {
            val clock = TestTimeSource()
            val visits = IntArray(2)
            var elapsed = 0L
            val model = ModelIdentity.of(Any())
            val workers = List(2) { arm ->
                trackingWorker("worker$arm", arm, ScriptedSearch({ arm == 0 && it == 0 }) {
                    visits[arm]++
                    elapsed += 500L
                    clock += 500.milliseconds
                }).bindEvidence(model, provesResults = arm == 0 || role != "witness").also {
                    it.restartingSearch = arm == 0 || role == "restarting"
                    if (arm == 1 && role == "improvement") it.improvementOnly = true
                }
            }

            Portfolio(workers, fixedBandit(2, 0), reseedStaleThreshold = 0).also {
                it.runTimeSource = clock
            }.use { it.minimize(Cancellation { elapsed >= 8_000L }) }

            assertEquals(1, visits[1], role)
        }
    }

    @Test
    fun `retired proof workers should leave coverage to live retained siblings`() {
        val clock = TestTimeSource()
        val elapsed = LongArray(3)
        val finished = TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted), onRun = {
            elapsed[1] += 100L
            clock += 100.milliseconds
        })
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) {
                elapsed[0] += 500L
                clock += 500.milliseconds
            }).also { it.restartingSearch = true },
            trackingWorker("finished", 1, finished),
            trackingWorker("live", 2, ScriptedSearch({ false }) {
                elapsed[2] += 100L
                clock += 100.milliseconds
            }),
        )

        Portfolio(workers, fixedBandit(3, 0), reseedStaleThreshold = 0).also {
            it.runTimeSource = clock
        }.use { it.minimize(Cancellation { elapsed.sum() >= 8_000L }) }

        assertEquals(1, finished.closes)
        assertTrue(elapsed[2] >= (elapsed[0] - 500L + elapsed[2]) / 2 - 500L)
    }
}
