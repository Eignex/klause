package com.eignex.klause.lp

import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceLpTest {
    @Test
    fun `failed and cancelled source operations report spending once`() {
        for (throws in listOf(false, true)) {
            val sink = SmtStatsSink()
            val budget = SourceLpBudget(onWork = sink::observeSourceLp)
            var cancelled = false
            runCatching {
                budget.run(emptyList(), 1, Cancellation { cancelled }) {
                    budget.observe(preparation = 7L, floatWork = 11L, continuation = 13L, refinement = 17L)
                    if (throws) error("source operation")
                    cancelled = true
                    true
                }
            }
            budget.closeOwner(AutoCloseable {})
            budget.closeOwner(AutoCloseable {})

            val reported = sink.snapshot().sourceLp
            assertEquals(1L, reported.operations)
            assertEquals(budget.reservedWork, reported.modeledWork)
            assertEquals(budget.reservedAllocation, reported.modeledAllocation)
            assertEquals(budget.activeNanos, reported.activeNs)
            assertEquals(7L, reported.preparationWork)
            assertEquals(11L, reported.floatWork)
            assertEquals(13L, reported.continuationWork)
            assertEquals(17L, reported.refinementWork)
        }
    }

    @Test
    fun `restoring original right hand side cannot reuse a changed conflict`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ONE))
        SourceLp(rows, 1, SourceLpBudget()).use { owner ->
            val changed = assertNotNull(
                owner.solve(Cancellation.Never, rhs = listOf(BigFraction.ZERO, BigFraction.MINUS_ONE)),
            )
            assertEquals(LpVerdict.INFEASIBLE, changed.verdict)

            val restored = assertNotNull(owner.solve(Cancellation.Never))

            assertNotNull(restored.exactPrimal)
            assertTrue(assertNotNull(restored.exactPrimal).satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `source row mutation cannot alter an admitted owner`() {
        val coefficients = mutableListOf(BigFraction.ONE)
        val row = ExactRationalInequality(intArrayOf(0), coefficients, BigFraction.ONE)
        SourceLp(listOf(row), 1, SourceLpBudget()).use { owner ->
            assertNotNull(owner.solve(Cancellation.Never, activity = 0))
            coefficients[0] = BigFraction.MINUS_ONE

            val result = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnLower(0, BigFraction.ofLong(2L)))),
            )

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        }
    }

    @Test
    fun `opposite singleton branch sides do not survive scope restoration`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ONE))
        SourceLp(rows, 1, SourceLpBudget()).use { owner ->
            val lower = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnLower(0, BigFraction.ONE))),
            )
            assertEquals(BigFraction.ONE, assertNotNull(lower.exactPrimal).first())

            val upper = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnUpper(0, BigFraction.ZERO))),
            )

            assertEquals(BigFraction.ZERO, assertNotNull(upper.exactPrimal).first())
        }
    }

    @Test
    fun `repeated branch forms reuse definitions without retaining their thresholds`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnLower(1, BigFraction.ZERO))
        val branch = ExactRationalInequality(
            intArrayOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ONE),
            BigFraction.ONE,
        )
        SourceLp(rows, 2, SourceLpBudget()).use { owner ->
            repeat(3) { assertNotNull(owner.solve(Cancellation.Never, branches = listOf(branch))) }

            val result = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnLower(0, BigFraction.ofLong(2L)))),
            )

            assertTrue(assertNotNull(result.exactPrimal).first() >= BigFraction.ofLong(2L))
        }
    }

    @Test
    fun `source family reservations survive replacement owners`() {
        val budget = SourceLpBudget(maxOperations = 1)
        SourceLp(emptyList(), 1, budget).use { assertNotNull(it.solve(Cancellation.Never)) }

        SourceLp(emptyList(), 1, budget).use { assertNull(it.solve(Cancellation.Never)) }

        assertEquals(1, budget.operations)
        assertTrue(budget.reservedWork > 0L)
        assertTrue(budget.reservedAllocation > 0L)
    }

    @Test
    fun `closing every owner preserves primary and suppressed failures`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        var closed = 0
        val failure = kotlin.test.assertFailsWith<IllegalStateException> {
            closeSourceLpOwners(
                listOf(
                    AutoCloseable {
                        closed++
                        throw first
                    },
                    AutoCloseable {
                        closed++
                        throw second
                    },
                ),
            )
        }

        assertEquals(first, failure)
        assertEquals(listOf(second), failure.suppressedExceptions.toList())
        assertEquals(2, closed)
    }
}
