package com.eignex.klause.portfolio

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.bandit.UnivariateBandit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PortfolioAuxiliaryTrialTest {
    @Test
    fun `auxiliary trial should complete preparation after admitting every sibling`() {
        val reached = ArrayList<Int>()
        val search = object : ResumableSearch {
            var slices = 0
            override val preparationPending: Boolean get() = slices < 3
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats.EMPTY
            override val work: Long get() = slices.toLong() * 5_000
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                reached += 1
                slices++
                return null
            }
        }
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
            trackingWorker("probe", 1, search),
            trackingWorker("sibling", 2, ScriptedSearch({ false }) { reached += 2 }),
        )

        Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 0).use {
            it.incumbentProbeArms = setOf(1)
            it.minimize(Cancellation { reached.size >= 20 })
        }

        assertEquals(listOf(0, 1, 2), reached.take(3))
        assertEquals(15, reached.count { it == 1 })
    }

    @Test
    fun `auxiliary trial should retain search through plateau reseeding`() {
        var calls = 0
        var slices = 0
        val search = object : ResumableSearch {
            private var closed = false
            private var spent = 0L
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats.EMPTY
            override val work: Long get() = spent
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                check(!closed)
                calls++
                slices++
                spent += sliceNodes
                return null
            }
            override fun close() {
                closed = true
            }
        }
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { calls++ }),
            trackingWorker("probe", 1, search).also { it.restartingSearch = true },
        )

        Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 1).use {
            it.incumbentProbeArms = setOf(1)
            it.minimize(Cancellation { calls >= 15 })
        }

        assertEquals(60_000L, search.work)
        assertTrue(slices > 1)
    }

    @Test
    fun `auxiliary search trial should retire after its charged work allowance`() {
        for ((maximum, charge, expectedSlices) in listOf(
            Triple(7L, 2L, 42),
            Triple(21L, 7L, 36),
            Triple(Long.MAX_VALUE, Long.MAX_VALUE, 1),
        )) {
            val reached = ArrayList<Int>()
            val search = object : ResumableSearch {
                private var spent = 0L
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override val work: Long get() = spent
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    reached += 1
                    spent += charge
                    return null
                }
            }
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
                trackingWorker("probe", 1, search),
            )

            Portfolio.thompson(workers, baseSliceWork = 7, maxSliceWork = maximum, reseedStaleThreshold = 0).use {
                it.incumbentProbeArms = setOf(1)
                it.minimize(Cancellation { reached.size >= expectedSlices + 3 })
            }

            assertEquals(expectedSlices, reached.count { it == 1 })
            assertEquals(listOf(0, 0), reached.takeLast(2))
        }
    }

    @Test
    fun `auxiliary search without charged work should have a bounded time trial`() {
        val clock = TestTimeSource()
        val reached = ArrayList<Int>()
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
            trackingWorker(
                "probe",
                1,
                TrackingResumableSearch(null, onRun = {
                    reached += 1
                    clock += 3_000.milliseconds
                }),
            ),
        )
        val portfolio = Portfolio.thompson(workers, reseedStaleThreshold = 0).also {
            it.incumbentProbeArms = setOf(1)
            it.runTimeSource = clock
        }

        portfolio.use { it.minimize(Cancellation { reached.size >= 6 }) }

        assertEquals(listOf(0, 1, 1, 1, 0, 0), reached)
    }

    @Test
    fun `debt repayment should not retire pending preparation or search`() {
        for (preparing in listOf(false, true)) {
            val reached = ArrayList<Int>()
            val search = object : ResumableSearch {
                private var slices = 0
                private var spent = 0L
                override val preparationPending: Boolean get() = preparing && slices < 15
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override val work: Long get() = spent
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    reached += 1
                    if (slices++ >= 15) spent += sliceNodes
                    return null
                }
            }
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
                trackingWorker("probe", 1, search),
            )

            Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 0).use {
                it.incumbentProbeArms = setOf(1)
                it.minimize(Cancellation { reached.size >= 30 })
            }

            assertEquals(60_000L, search.work)
            assertEquals(listOf(0, 0), reached.takeLast(2))
        }
    }

    @Test
    fun `auxiliary trial should bound preparation that cannot enter search`() {
        val clock = TestTimeSource()
        val reached = ArrayList<Int>()
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
            trackingWorker(
                "probe",
                1,
                TrackingResumableSearch(null, onRun = {
                    reached += 1
                    clock += 3_000.milliseconds
                }, preparationPending = true),
            ),
        )
        val portfolio = Portfolio.thompson(workers, reseedStaleThreshold = 0).also {
            it.incumbentProbeArms = setOf(1)
            it.runTimeSource = clock
        }

        portfolio.use { it.minimize(Cancellation { reached.size >= 11 }) }

        assertEquals(8, reached.count { it == 1 })
        assertEquals(listOf(0, 0), reached.takeLast(2))
    }

    @Test
    fun `unproductive auxiliary work should not update retained arm bandit evidence`() {
        for (seed in 0L..1L) {
            val reached = ArrayList<Int>()
            val updates = ArrayList<Int>()
            val workers = listOf(
                trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { reached += 0 }),
                trackingWorker("idle", 1, ScriptedSearch({ false }) { reached += 1 }).also {
                    it.family = ArmFamily.LocalSearch
                },
                trackingWorker("probe", 2, ScriptedSearch({ false }) { reached += 2 }),
            )
            val bandit = object : UnivariateBandit {
                override val nbrArms = 3
                override val random = Random(seed)
                override fun choose() = 0
                override fun update(armIndex: Int, value: Double, weight: Double) {
                    updates += armIndex
                }
                override fun reset() = Unit
            }

            Portfolio(workers, bandit, maxSliceWork = 5_000L, reseedStaleThreshold = 0).use {
                it.incumbentProbeArms = setOf(2)
                it.minimize(Cancellation { reached.count { arm -> arm < 2 } >= 30 })
            }

            assertEquals(reached.filter { it < 2 }, updates)
        }
    }

    @Test
    fun `auxiliary trial should preserve subsequent retained policy choices`() {
        fun choices(continueTrial: Boolean, admittedBeforeIncumbent: Boolean, withProof: Boolean): List<Int> {
            val reached = ArrayList<Int>()
            var finderVisited = false
            val probe = ScriptedSearch({ false }) {}
            val trial = object : ResumableSearch by probe {
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    probe.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                    return if (continueTrial || !finderVisited) {
                        null
                    } else {
                        MinimizeResult.Unknown(TerminationReason.BudgetExhausted)
                    }
                }
            }
            val finder = trackingWorker(
                "finder",
                0,
                ScriptedSearch({ it == 0 }) {
                    reached += 0
                    finderVisited = true
                },
            ).also { it.family = ArmFamily.LocalSearch }
            val retained = trackingWorker("retained", 1, ScriptedSearch({ false }) { reached += 1 })
            val auxiliary = trackingWorker("probe", 2, trial)
            val workers = if (admittedBeforeIncumbent) {
                mutableListOf(auxiliary, finder, retained)
            } else {
                mutableListOf(finder, retained, auxiliary)
            }
            if (withProof) {
                var proofVisits = 0
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
                workers.add(
                    trackingWorker("proof", 3, proof).also {
                        it.auxiliaryProof = true
                        it.improvementOnly = true
                    },
                )
            }

            Portfolio.thompson(workers, seed = 0, maxSliceWork = 5_000L, reseedStaleThreshold = 0).use {
                it.incumbentProbeArms = setOf(if (admittedBeforeIncumbent) 0 else 2)
                it.minimize(Cancellation { reached.size >= 16 })
            }
            return reached
        }

        for (admittedBeforeIncumbent in listOf(false, true)) {
            for (withProof in listOf(false, true)) {
                assertEquals(
                    choices(false, admittedBeforeIncumbent, withProof),
                    choices(true, admittedBeforeIncumbent, withProof),
                )
            }
        }
    }

    @Test
    fun `auxiliary continuations should preserve ordinary family allowances`() {
        for (charged in listOf(false, true)) {
            val clock = TestTimeSource()
            val allowances = ArrayList<Long>()
            val finder = ScriptedSearch({ it == 0 }) {}
            val observedFinder = object : ResumableSearch by finder {
                override fun runSlice(
                    global: Cancellation,
                    sliceMillis: Long,
                    sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit,
                ): MinimizeResult? {
                    allowances += sliceNodes
                    return finder.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
                }
            }
            val probe = ScriptedSearch({ false }) { clock += 1_000.milliseconds }
            val observedProbe = object : ResumableSearch by probe {
                override val work: Long get() = if (charged) probe.work else 0L
            }
            val workers = listOf(
                trackingWorker("finder", 0, observedFinder),
                trackingWorker("probe", 1, observedProbe),
            )
            val portfolio = Portfolio.thompson(
                workers,
                baseSliceWork = 7,
                maxSliceWork = 21,
                reseedStaleThreshold = 0,
            ).also {
                it.incumbentProbeArms = setOf(1)
                it.runTimeSource = clock
            }

            portfolio.use { it.minimize(Cancellation { allowances.size >= 2 }) }

            assertEquals(listOf(7L, 7L), allowances)
        }
    }

    @Test
    fun `auxiliary continuations should reuse the current family allowance`() {
        val allowances = ArrayList<Long>()
        var finderSlices = 0
        val probe = ScriptedSearch({ false }) {}
        val observed = object : ResumableSearch by probe {
            override fun runSlice(
                global: Cancellation,
                sliceMillis: Long,
                sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit,
            ): MinimizeResult? {
                allowances += sliceNodes
                return probe.runSlice(global, sliceMillis, sliceNodes, onIncumbent)
            }
        }
        val workers = listOf(
            trackingWorker("finder", 0, ScriptedSearch({ it == 0 }) { finderSlices++ }),
            trackingWorker("probe", 1, observed),
        )

        Portfolio.thompson(workers, baseSliceWork = 7, maxSliceWork = 21, reseedStaleThreshold = 0).use {
            it.incumbentProbeArms = setOf(1)
            it.minimize(Cancellation { finderSlices >= 2 })
        }

        assertEquals(List(36) { 7L }, allowances)
    }
}
