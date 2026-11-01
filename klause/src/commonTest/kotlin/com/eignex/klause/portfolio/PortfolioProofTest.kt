package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchStats
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stat.summary.SumResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class PortfolioProofTest {
    @Test
    fun `proof continuation should follow charged family work without waiting for every sibling`() {
        var ordinaryVisits = 0
        var proofVisits = 0
        val progressing = ScriptedSearch({ it == 0 || it >= 2 }) { ordinaryVisits++ }
        val quietBase = ScriptedSearch({ false }) { ordinaryVisits++ }
        val quiet = object : ResumableSearch by quietBase {
            override val work: Long get() = minOf(5_000L, quietBase.work)
        }
        val proofBase = ScriptedSearch({ false }) { proofVisits++ }
        val proof = object : ResumableSearch by proofBase {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (proofVisits == 3) MinimizeResult.Unknown(TerminationReason.BudgetExhausted) else null
            }
        }
        val workers = listOf(
            trackingWorker("progressing", 0, progressing),
            trackingWorker("quiet", 1, quiet),
            trackingWorker("proof", 2, proof).also {
                it.auxiliaryProof = true
                it.improvementOnly = true
            },
        )

        Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).also {
            it.runTimeSource = TestTimeSource()
        }.use {
            it.minimize(Cancellation { proofVisits >= 3 || ordinaryVisits >= 32 })
        }

        assertEquals(3, proofVisits)
        assertEquals(5_000L, quiet.work)
    }

    @Test
    fun `proof continuations should keep base work and receive a family deadline`() {
        var ordinaryVisits = 0
        val complete = trackingWorker("complete", 0, ScriptedSearch({ false }) { ordinaryVisits++ })
        val finder = trackingWorker("finder", 1, ScriptedSearch({ it == 0 }) { ordinaryVisits++ }).also {
            it.family = ArmFamily.LocalSearch
        }
        val quiet = List(2) { index ->
            trackingWorker("quiet$index", index + 2, ScriptedSearch({ false }) { ordinaryVisits++ }).also {
                it.family = ArmFamily.LocalSearch
            }
        }
        val offers = ArrayList<Pair<Long, Long>>()
        val proofBase = ScriptedSearch({ false }) {}
        val proof = object : ResumableSearch by proofBase {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                offers += sliceMillis to sliceNodes
                proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return if (offers.size == 2) MinimizeResult.Unknown(TerminationReason.BudgetExhausted) else null
            }
        }
        val workers = listOf(complete, finder) + quiet + trackingWorker("proof", 4, proof).also {
            it.auxiliaryProof = true
            it.improvementOnly = true
        }

        Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).use {
            it.minimize(Cancellation.after(1.seconds) or Cancellation { offers.size >= 2 || ordinaryVisits >= 16 })
        }

        assertEquals(2, offers.size)
        assertEquals(listOf(5_000L, 5_000L), offers.map { it.second })
        assertTrue(offers[1].first > offers[0].first * 1.5)
    }

    @Test
    fun `supplemental proof continuations should wait for fresh sibling work`() {
        for (improvesAfterAdmission in listOf(false, true)) {
            var completeVisits = 0
            var lastCompleteVisit = 0
            var proofVisits = 0
            val complete = ScriptedSearch({ it == 0 || (improvesAfterAdmission && it >= 2) }) { completeVisits++ }
            val proofBase = ScriptedSearch({ false }) {
                assertTrue(completeVisits > lastCompleteVisit)
                lastCompleteVisit = completeVisits
                proofVisits++
            }
            val proof = object : ResumableSearch by proofBase {
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                    return if (proofVisits == 3) MinimizeResult.Unknown(TerminationReason.BudgetExhausted) else null
                }
            }
            val workers = listOf(
                trackingWorker("complete", 0, complete),
                trackingWorker("proof", 1, proof).also {
                    it.auxiliaryProof = true
                    it.improvementOnly = true
                    it.retainInitialProofCoverage = true
                },
            )

            Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).use {
                it.minimize(Cancellation { proofVisits >= 3 || completeVisits >= 8 })
            }

            assertEquals(3, proofVisits)
        }
    }

    @Test
    fun `supplemental proof should wait for sibling work after the latest improvement`() {
        var completeVisits = 0
        var proofVisits = 0
        val complete = ScriptedSearch({ it < 3 }) { completeVisits++ }
        val proofBase = ScriptedSearch({ false }) {
            assertEquals(4, completeVisits)
            proofVisits++
        }
        val proof = object : ResumableSearch by proofBase {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        val workers = listOf(
            trackingWorker("complete", 0, complete),
            trackingWorker("proof", 1, proof).also {
                it.auxiliaryProof = true
                it.improvementOnly = true
            },
        )

        Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).use {
            it.minimize(Cancellation { proofVisits > 0 })
        }

        assertEquals(1, proofVisits)
    }

    @Test
    fun `continued incumbent improvements should keep supplemental proof deferred`() {
        var completeVisits = 0
        var proofVisits = 0
        val complete = ScriptedSearch({ true }) { completeVisits++ }
        val proof = ScriptedSearch({ false }) { proofVisits++ }
        val workers = listOf(
            trackingWorker("complete", 0, complete),
            trackingWorker("proof", 1, proof).also {
                it.auxiliaryProof = true
                it.improvementOnly = true
            },
        )

        Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).use {
            it.minimize(Cancellation { completeVisits >= 8 })
        }

        assertEquals(8, completeVisits)
        assertEquals(0, proofVisits)
    }

    @Test
    fun `first core trial should retain every complete siblings charged coverage`() {
        for (initialWork in listOf(0L, 2_500L, 5_000L)) {
            val visits = IntArray(2)
            var proofVisits = 0
            val complete = List(2) { arm ->
                val base = ScriptedSearch({ true }) { visits[arm]++ }
                object : ResumableSearch by base {
                    override val work: Long get() = if (visits[arm] == 1) initialWork else base.work
                }
            }
            val proof = ScriptedSearch({ false }) {
                assertTrue(complete.all { it.work >= 5_000L })
                proofVisits++
            }
            val workers = complete.mapIndexed { arm, search -> trackingWorker("complete$arm", arm, search) } +
                trackingWorker("core", 2, proof).also {
                    it.auxiliaryProof = true
                    it.improvementOnly = true
                    it.retainInitialProofCoverage = true
                }

            Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).use {
                it.minimize(Cancellation { proofVisits > 0 || visits.sum() >= 8 })
            }

            assertEquals(1, proofVisits)
        }
    }

    @Test
    fun `unspent complete work offers should not admit a supplemental proof service`() {
        var completeVisits = 0
        var proofVisits = 0
        val finder = trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) {}).also {
            it.family = ArmFamily.LocalSearch
        }
        val completeBase = ScriptedSearch({ false }) { completeVisits++ }
        val complete = object : ResumableSearch by completeBase {
            override val work: Long get() = if (completeVisits < 2) 0L else completeBase.work
        }
        val proofBase = ScriptedSearch({ false }) {
            assertTrue(completeVisits >= 2)
            proofVisits++
        }
        val proof = object : ResumableSearch by proofBase {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                return MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        val workers = listOf(
            finder,
            trackingWorker("complete", 1, complete),
            trackingWorker("proof", 2, proof).also {
                it.auxiliaryProof = true
                it.improvementOnly = true
            },
        )

        Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).use {
            it.minimize(Cancellation { proofVisits > 0 })
        }

        assertEquals(1, proofVisits)
    }

    @Test
    fun `bounded proof preparation should preserve ordinary policy choices and work offers`() {
        fun trace(auxiliary: Boolean, auxiliaryFirst: Boolean): List<Pair<Int, Long>> {
            val reached = ArrayList<Pair<Int, Long>>()
            val finderBase = ScriptedSearch({ it == 0 }) {}
            val finder = object : ResumableSearch by finderBase {
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    reached += 0 to sliceNodes
                    return finderBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                }
            }
            val retainedBase = ScriptedSearch({ false }) {}
            val retained = object : ResumableSearch by retainedBase {
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    reached += 1 to sliceNodes
                    return retainedBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                }
            }
            var visits = 0
            val proofBase = ScriptedSearch({ false }) { visits++ }
            val proof = object : ResumableSearch by proofBase {
                override val preparationPending: Boolean get() = visits < 2
                override val stats: SolveStats get() = SolveStats(
                    search = SearchStats(glueClauses = SumResult(visits * 1_000.0)),
                )
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    proofBase.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                    return if (visits < 3) null else MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                }
            }
            val workers = mutableListOf(
                trackingWorker("finder", 0, finder).also { it.family = ArmFamily.LocalSearch },
                trackingWorker("retained", 1, retained),
            )
            if (auxiliary) {
                workers.add(
                    if (auxiliaryFirst) 0 else workers.size,
                    trackingWorker("proof", 2, proof).also { it.auxiliaryProof = true },
                )
            }

            val result = Portfolio.thompson(workers, seed = 0, reseedStaleThreshold = 0).also {
                it.runTimeSource = TestTimeSource()
            }.use {
                it.minimize(Cancellation { reached.size >= 16 })
            }

            if (auxiliary) {
                val schedule = result.stats.portfolio.arms.single { it.label == "proof" }
                assertEquals(3L, schedule.segments)
                assertEquals(15_000L, schedule.work)
                assertEquals(3_000.0, schedule.credit["Glue"])
            }
            return reached
        }

        for (auxiliaryFirst in listOf(false, true)) {
            assertEquals(trace(false, auxiliaryFirst), trace(true, auxiliaryFirst))
        }
    }
}
