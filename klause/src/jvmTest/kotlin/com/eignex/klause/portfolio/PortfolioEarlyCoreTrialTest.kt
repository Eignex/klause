package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TestTimeSource

@RunWith(Parameterized::class)
class PortfolioEarlyCoreTrialTest(private val incumbentBeforeTrial: Boolean) {
    @Test
    fun `early core should receive a trial after complete coverage before heuristic probes`() {
        val reached = ArrayList<Int>()
        val workers = listOf(
            trackingWorker("complete-first", 0, ScriptedSearch({ incumbentBeforeTrial && it == 0 }) { reached += 0 }),
            trackingWorker("complete-second", 1, ScriptedSearch({ false }) { reached += 1 }),
            trackingWorker("heuristic", 2, ScriptedSearch({ false }) { reached += 2 }).also {
                it.family = ArmFamily.LocalSearch
            },
            trackingWorker("core", 3, ScriptedSearch({ false }) { reached += 3 }).also {
                it.auxiliaryProof = true
                it.earlyCoreTrial = true
            },
        )

        Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 0).also {
            it.runTimeSource = TestTimeSource()
        }.use { it.minimize(Cancellation { reached.size >= 3 }) }

        assertEquals(listOf(0, 1, 3), reached)
    }

    @Test
    fun `an early core should retain its unfinished trial before heuristic probes`() {
        val reached = ArrayList<Int>()
        val workers = listOf(
            trackingWorker("complete-first", 0, ScriptedSearch({ incumbentBeforeTrial && it == 0 }) { reached += 0 }),
            trackingWorker("complete-second", 1, ScriptedSearch({ false }) { reached += 1 }),
            trackingWorker("heuristic", 2, ScriptedSearch({ false }) { reached += 2 }).also {
                it.family = ArmFamily.LocalSearch
            },
            trackingWorker("core", 3, ScriptedSearch({ false }) { reached += 3 }).also {
                it.auxiliaryProof = true
                it.earlyCoreTrial = true
            },
        )

        Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 0).also {
            it.runTimeSource = TestTimeSource()
        }.use { it.minimize(Cancellation { reached.size >= 4 }) }

        assertEquals(listOf(0, 1, 3, 3), reached)
    }

    @Test
    fun `a completed early core trial should admit the heuristic probe`() {
        val reached = ArrayList<Int>()
        val core = TrackingResumableSearch(MinimizeResult.Unknown(TerminationReason.BudgetExhausted), onRun = {
            reached += 3
        })
        val workers = listOf(
            trackingWorker("complete-first", 0, ScriptedSearch({ incumbentBeforeTrial && it == 0 }) { reached += 0 }),
            trackingWorker("complete-second", 1, ScriptedSearch({ false }) { reached += 1 }),
            trackingWorker("heuristic", 2, ScriptedSearch({ false }) { reached += 2 }).also {
                it.family = ArmFamily.LocalSearch
            },
            trackingWorker("core", 3, core).also {
                it.auxiliaryProof = true
                it.earlyCoreTrial = true
            },
        )

        Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 0).also {
            it.runTimeSource = TestTimeSource()
        }.use { it.minimize(Cancellation { reached.size >= 4 }) }

        assertEquals(listOf(0, 1, 3, 2), reached)
    }

    @Test
    fun `an ordinary auxiliary core should retain heuristic probe order`() {
        val reached = ArrayList<Int>()
        val workers = listOf(
            trackingWorker("complete-first", 0, ScriptedSearch({ incumbentBeforeTrial && it == 0 }) { reached += 0 }),
            trackingWorker("complete-second", 1, ScriptedSearch({ false }) { reached += 1 }),
            trackingWorker("heuristic", 2, ScriptedSearch({ false }) { reached += 2 }).also {
                it.family = ArmFamily.LocalSearch
            },
            trackingWorker("core", 3, ScriptedSearch({ false }) { reached += 3 }).also {
                it.auxiliaryProof = true
            },
        )

        Portfolio.thompson(workers, maxSliceWork = 5_000L, reseedStaleThreshold = 0).also {
            it.runTimeSource = TestTimeSource()
        }.use { it.minimize(Cancellation { reached.size >= 3 }) }

        assertEquals(listOf(0, 1, 2), reached)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "incumbentBeforeTrial={0}")
        fun parameters(): List<Array<Any>> = listOf(false, true).map { arrayOf<Any>(it) }
    }
}
