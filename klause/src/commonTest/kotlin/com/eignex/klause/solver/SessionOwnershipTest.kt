package com.eignex.klause.solver

import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionOwnershipTest {
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
