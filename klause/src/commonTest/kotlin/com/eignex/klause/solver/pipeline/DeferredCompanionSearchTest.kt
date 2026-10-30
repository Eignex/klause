package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.InstructionSlicedSearch
import com.eignex.klause.solver.ResumableSearch
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
import kotlin.time.TestTimeSource

class DeferredCompanionSearchTest {
    @Test
    fun `construction that fills a query should retain the worker for a later grant`() {
        val clock = TestTimeSource()
        var opened = 0
        var queries = 0
        val search = DeferredCompanionSearch({ TerminationReason.Unsupported }, clock) { _, _ ->
            opened++
            clock += 100.milliseconds
            object : ResumableSearch {
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                    assertEquals(100L, sliceMillis)
                    queries++
                    return null
                }
            }
        }

        assertNull(search.runSlice(Cancellation.Never, 100, 7) {})
        assertEquals(0, queries)
        assertNull(search.runSlice(Cancellation.Never, 100, 7) {})

        assertEquals(1, opened)
        assertEquals(1, queries)
    }

    @Test
    fun `opening a companion should spend its containing query and active lifetime`() {
        for (instruction in listOf(false, true)) {
            val clock = TestTimeSource()
            var opened = 0
            var closed = 0
            var queries = 0
            val deferred = DeferredCompanionSearch({ TerminationReason.Unsupported }, clock) { _, _ ->
                opened++
                clock += 40.milliseconds
                object : InstructionSlicedSearch {
                    override val isDone: Boolean get() = false
                    override val stats: SolveStats get() = SolveStats.EMPTY
                    override val chargedInstructions: Long get() = 17L
                    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
                        runSlice(global, sliceMillis, sliceInstructions, onIncumbent)
                    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                        onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? {
                        assertEquals(60L, sliceMillis)
                        assertEquals(7L, sliceNodes)
                        queries++
                        clock += 60.milliseconds
                        return null
                    }
                    override fun close() { closed++ }
                }
            }
            val bounded = BoundedCompanionSearch(deferred, "funded", activeLimit = 100.milliseconds, clock = clock)

            assertEquals(0, opened)
            assertTrue(bounded.incumbentTrialPending)
            val first = if (instruction) bounded.runInstructionSlice(Cancellation.Never, 100, 7) {}
            else bounded.runSlice(Cancellation.Never, 100, 7) {}
            assertNull(first)
            val terminal = bounded.runSlice(Cancellation.Never, 100, 7) {}
            bounded.close()
            bounded.close()

            assertIs<MinimizeResult.Unknown>(terminal)
            assertEquals(1, opened)
            assertEquals(1, queries)
            assertEquals(1, closed)
            assertEquals(17L, bounded.chargedInstructions)
            assertEquals(100L, bounded.stats.smt.completeSearchStages?.get("fundedActiveMs"))
        }
    }

    @Test
    fun `an unfunded start should retire without creating a native worker`() {
        var opened = 0
        val search = DeferredCompanionSearch({ TerminationReason.BudgetExhausted }) { _, _ -> opened++; null }

        val result = search.runSlice(Cancellation.Never, 100, 7) {}
        search.runSlice(Cancellation.Never, 100, 7) {}

        assertEquals(TerminationReason.BudgetExhausted, assertIs<MinimizeResult.Unknown>(result).reason)
        assertEquals(1, opened)
        assertFalse(search.incumbentTrialPending)
    }
}
