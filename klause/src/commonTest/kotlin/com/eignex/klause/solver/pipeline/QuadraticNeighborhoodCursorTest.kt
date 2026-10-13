package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class QuadraticNeighborhoodCursorTest {
    @Test
    fun `adaptive proposals and widening survive cancelled sibling turns`() {
        val whole = mutableListOf<ExactNeighborhoodCondition>()
        val sliced = mutableListOf<ExactNeighborhoodCondition>()
        val wholeAllowance = QuadraticNeighborhoodAllowance(4, 100, null)
        val slicedAllowance = QuadraticNeighborhoodAllowance(4, 100, null)
        val completion = ExactNeighborhoodCompletion { _, _ -> ExactNeighborhoodResult.Refuted }
        val unpaused = QuadraticNeighborhoodCursor(
            2, true, 7, wholeAllowance, Cancellation.Never, { 0 },
            { released, radius, random ->
                ExactNeighborhoodCondition(mapOf(0 to random.nextBoolean()),
                    mapOf(0 to if (0 in released) radius.negated() else BigFraction.ZERO), mapOf(0 to radius))
                    .also { whole += it }
            }, completion, { false },
        )
        val paused = QuadraticNeighborhoodCursor(
            2, true, 7, slicedAllowance, Cancellation.Never, { 0 },
            { released, radius, random ->
                ExactNeighborhoodCondition(mapOf(0 to random.nextBoolean()),
                    mapOf(0 to if (0 in released) radius.negated() else BigFraction.ZERO), mapOf(0 to radius))
                    .also { sliced += it }
            }, completion, { false },
        )

        unpaused.use { assertTrue(it.advance(Cancellation.Never, Long.MAX_VALUE)) }
        paused.use {
            assertFalse(it.advance(Cancellation.Never, 1))
            assertFalse(it.advance(Cancellation { true }, 100))
            assertFalse(it.advance(Cancellation.Never, 1))
            assertTrue(it.advance(Cancellation.Never, 100))
        }

        assertEquals(whole, sliced)
        assertEquals(wholeAllowance.instructions, slicedAllowance.instructions)
    }

    @Test
    fun `reopening a neighborhood does not renew its containing move quota`() {
        val allowance = QuadraticNeighborhoodAllowance(2, 100, null)
        var queries = 0
        val completion = ExactNeighborhoodCompletion { _, _ ->
            queries++
            ExactNeighborhoodResult.Undecided
        }
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        val first = QuadraticNeighborhoodCursor(1, false, 1, allowance, Cancellation.Never, { 0 },
            { _, _, _ -> condition }, completion, { false })
        first.use { assertFalse(it.advance(Cancellation.Never, 1)) }
        val reopened = QuadraticNeighborhoodCursor(1, false, 3, allowance, Cancellation.Never, { 0 },
            { _, _, _ -> condition }, completion, { false })

        reopened.use {
            assertEquals(0L, it.work)
            assertTrue(it.advance(Cancellation.Never, 100))
            assertEquals(1L, it.work)
        }

        assertEquals(2, queries)
        assertEquals(2L, allowance.moves)
    }

    @Test
    fun `atomic repair work charges the shared allowance once`() {
        val budget = NodeBudget(5)
        val allowance = QuadraticNeighborhoodAllowance(10, 100, budget)
        var nativeWork = 0L
        val completion = ExactNeighborhoodCompletion { _, _ ->
            nativeWork += 5
            ExactNeighborhoodResult.Undecided
        }
        val cursor = QuadraticNeighborhoodCursor(1, true, 1, allowance, Cancellation.Never, { nativeWork },
            { _, _, _ -> ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()) }, completion, { false })

        cursor.use { assertTrue(it.advance(Cancellation.Never, 1)) }

        assertEquals(1L, allowance.moves)
        assertEquals(5L, allowance.instructions)
        assertEquals(0L, budget.decisionsLeft())
    }

    @Test
    fun `closed neighborhoods reject another segment without spending work`() {
        val allowance = QuadraticNeighborhoodAllowance(10, 100, null)
        val cursor = QuadraticNeighborhoodCursor(1, true, 1, allowance, Cancellation.Never, { 0 },
            { _, _, _ -> error("closed cursor cannot propose") },
            ExactNeighborhoodCompletion { _, _ -> error("closed cursor cannot complete") }, { false })

        cursor.close()
        cursor.close()

        assertFailsWith<IllegalStateException> { cursor.advance(Cancellation.Never, 1) }
        assertEquals(0L, allowance.instructions)
    }

    @Test
    fun `invocation cancellation reaches an atomic repair without using the segment deadline`() {
        val allowance = QuadraticNeighborhoodAllowance(10, 100, null)
        var cancelled = false
        var reached = false
        val invocation = Cancellation { cancelled }
        val cursor = QuadraticNeighborhoodCursor(1, false, 1, allowance, Cancellation.Never, { 0 },
            { _, _, _ -> ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()) },
            ExactNeighborhoodCompletion { _, stop ->
                assertFalse(stop())
                cancelled = true
                reached = stop()
                ExactNeighborhoodResult.Undecided
            }, { false })

        cursor.use { it.advance(Cancellation.Never, 100, invocation) }

        assertTrue(reached)
        assertEquals(1L, allowance.moves)
    }
}
