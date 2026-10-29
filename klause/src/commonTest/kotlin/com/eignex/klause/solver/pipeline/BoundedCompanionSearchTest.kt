package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class BoundedCompanionSearchTest {
    @Test
    fun `an overrun should retire a companion without claiming exhaustion`() {
        for (ceiling in listOf(250L, 1_000L)) {
            val clock = TestTimeSource()
            var calls = 0
            val native = object : ResumableSearch, ReseedGuard {
                override val canReseed: Boolean get() = false
                override val incumbentTrialPending: Boolean get() = true
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                    assertEquals(ceiling, sliceMillis)
                    assertEquals(5_000L, sliceNodes)
                    calls++
                    clock += 5.seconds
                    return null
                }
            }

            val search = BoundedCompanionSearch(native, "trial", sliceLimit = ceiling.milliseconds, clock = clock)
            assertNull(search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {})
            assertFalse(search.canReseed)
            val result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) {}

            assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
            assertEquals(1, calls)
            assertTrue(search.isDone)
            assertFalse(search.incumbentTrialPending)
            assertEquals(1L, search.stats.smt.completeSearchStages?.get("trialRetired"))
            assertEquals(5_000L, search.stats.smt.completeSearchStages?.get("trialActiveMs"))
        }
    }

    @Test
    fun `a completed proof should survive its final segment overrun`() {
        val clock = TestTimeSource()
        val sample = Sample(booleanArrayOf(), longArrayOf())
        val native = object : ResumableSearch {
            override val isDone: Boolean get() = true
            override val stats: SolveStats get() = SolveStats.EMPTY
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult {
                assertEquals(10L, sliceMillis)
                assertEquals(7L, sliceNodes)
                clock += 20.milliseconds
                return MinimizeResult.Optimal(sample, 0.0)
            }
        }
        val search = BoundedCompanionSearch(native, "trial", activeLimit = 10.milliseconds, clock = clock)

        val result = search.runSlice(Cancellation.Never, 12L, 7L) {}

        assertIs<MinimizeResult.Optimal>(result)
        assertEquals(1L, search.stats.smt.completeSearchStages?.get("trialOptimal"))
        assertEquals(0L, search.stats.smt.completeSearchStages?.get("trialRetired"))
    }
}
