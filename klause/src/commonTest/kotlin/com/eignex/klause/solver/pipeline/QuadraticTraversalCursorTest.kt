package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuadraticTraversalCursorTest {
    @Test
    fun `decision slices complete every pending finite candidate exactly once`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray()).bake()
        val completed = mutableListOf<Long>()
        val sink = SolveStatsSink("quadratic").also { it.start() }
        val cursor = QuadraticTraversalCursor(source, BacktrackParams(), sink, { 0 }) { sample, _ ->
            completed += sample.intValue(0)
            QuadraticBranchResult.Unknown
        }
        var exhausted: SearchOutcome? = null

        cursor.use {
            repeat(20) {
                if (!cursor.isDone) {
                    exhausted = cursor.advance(Cancellation.Never, Long.MAX_VALUE, 1) { true }
                }
            }
        }

        assertIs<SearchOutcome.Exhausted>(exhausted)
        assertEquals(listOf(0L, 1L, 2L), completed.sorted())
    }

    @Test
    fun `native completion overspend pauses before another finite assignment`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()).bake()
        var native = 0L
        val completed = mutableListOf<Long>()
        val sink = SolveStatsSink("quadratic").also { it.start() }
        val cursor = QuadraticTraversalCursor(source, BacktrackParams(), sink, { native }) { sample, _ ->
            native += 10
            completed += sample.intValue(0)
            QuadraticBranchResult.Unknown
        }

        cursor.use {
            repeat(10) {
                if (completed.isEmpty()) cursor.advance(Cancellation.Never, Long.MAX_VALUE, 1) { true }
            }
            assertEquals(1, completed.size)
            val before = cursor.work
            assertNull(cursor.advance(Cancellation.Never, Long.MAX_VALUE, 1) { true })
            assertEquals(before, cursor.work)
            assertEquals(1, completed.size)
            repeat(30) {
                if (!cursor.isDone) cursor.advance(Cancellation.Never, Long.MAX_VALUE, 1) { true }
            }
        }

        assertTrue(cursor.isDone)
        assertEquals(listOf(0L, 1L), completed.sorted())
        assertEquals(20L, native)
    }

    @Test
    fun `closing traversal discards a pending candidate and rejects resumption`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray()).bake()
        val sink = SolveStatsSink("quadratic").also { it.start() }
        val cursor = QuadraticTraversalCursor(source, BacktrackParams(), sink, { 0 }) { _, _ ->
            error("zero slice cannot complete a candidate")
        }
        assertNull(cursor.advance(Cancellation.Never, Long.MAX_VALUE, 0) { true })

        cursor.close()
        cursor.close()

        assertFailsWith<IllegalStateException> {
            cursor.advance(Cancellation.Never, Long.MAX_VALUE, 1) { true }
        }
    }
}
