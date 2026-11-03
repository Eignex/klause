package com.eignex.klause.solver

import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionOwnershipTest {
    @Test
    fun `owned searches should forward live incumbent trial requests`() {
        for (counted in listOf(false, true)) {
            var pending = false
            val source = object : ResumableSearch, ReseedGuard {
                override val canReseed: Boolean get() = false
                override val incumbentTrialPending: Boolean get() = pending
                override val isDone: Boolean get() = false
                override val stats: SolveStats get() = SolveStats.EMPTY
                override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long,
                    onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? = null
            }
            val child = if (counted) {
                object : ResumableSearch by source, InstructionSlicedSearch, ReseedGuard by source {
                    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long,
                        sliceInstructions: Long, onIncumbent: (MinimizeResult.WithSample) -> Unit): MinimizeResult? =
                        source.runSlice(global, sliceMillis, sliceInstructions, onIncumbent)
                }
            } else source

            ownSearch(child) {}.use { owned ->
                val guard = assertIs<ReseedGuard>(owned)
                assertFalse(guard.incumbentTrialPending)
                pending = true
                assertTrue(guard.incumbentTrialPending)
                pending = false
                assertFalse(guard.incumbentTrialPending)
            }
        }
    }

    @Test
    fun `owned handles should preserve a shared search and cleanup failure`() {
        val failure = IllegalArgumentException("shared failure")
        var releases = 0
        val source = object : ResumableSolve {
            override val isDone: Boolean get() = false
            override val stats: SolveStats get() = SolveStats.EMPTY
            override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult =
                throw failure
            override fun close(): Unit = throw failure
        }
        val owned = ownSolve(source) { releases++ }

        assertSame(failure, assertFailsWith<IllegalArgumentException> {
            owned.runSlice(Cancellation.Never, 1L, 1L)
        })

        assertTrue(failure.suppressedExceptions.isEmpty())
        assertEquals(1, releases)
        owned.close()
        assertEquals(1, releases)
    }
}
