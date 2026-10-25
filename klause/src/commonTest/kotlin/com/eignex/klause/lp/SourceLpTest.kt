package com.eignex.klause.lp

import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

class SourceLpTest {
    @Test
    fun `small input admission should distinguish dimension entry and scalar bit declines`() {
        val cases = listOf(
            "Dimension" to SourceLpInput(emptyList(), 129, emptyList()),
            "Entries" to SourceLpInput(List(9) {
                ExactRationalInequality(IntArray(64) { it }, List(64) { BigFraction.ONE }, BigFraction.ZERO)
            }, 64, emptyList()),
            "InputBits" to SourceLpInput(List(2) {
                ExactRationalInequality(IntArray(65) { it }, List(65) { BigFraction.ofLong(Long.MAX_VALUE) },
                    BigFraction.ZERO)
            }, 65, emptyList()),
        )
        for ((reason, input) in cases) {
            val declines = mutableListOf<String>()

            val result = SourceLpBudget().runPrepared(Cancellation.Never, prepare = { input },
                onDecline = { declines += it }) { error("declined input") }

            assertNull(result)
            assertEquals(listOf(reason), declines)
        }
    }

    @Test
    fun `caller funded declines can continue past the legacy operation ceiling`() {
        val funding = SourceLpFunding(300_000, 10_000)
        val budget = SourceLpBudget(maxOperations = Int.MAX_VALUE, funding = funding)
        funding.grant(300_000)

        repeat(257) {
            budget.runPrepared(Cancellation.Never, prepare = { null }) { true }
        }

        assertEquals(257, budget.operations)
        assertEquals(257L * 1024L, funding.spent)
        assertFalse(funding.exhausted)
    }

    @Test
    fun `unfunded preparation can resume without renewing measured work`() {
        val funding = SourceLpFunding(10_000, 10_000)
        val budget = SourceLpBudget(maxOperations = Int.MAX_VALUE, funding = funding)
        var prepared = 0
        fun attempt() = budget.runPrepared(Cancellation.Never, prepare = {
            prepared++
            null
        }) { true }

        assertNull(attempt())
        assertEquals(0, prepared)
        funding.grant(1024)
        assertNull(attempt())
        assertNull(attempt())
        assertEquals(1, prepared)
        funding.grant(1024)
        assertNull(attempt())
        assertEquals(2, prepared)
        assertEquals(2048L, funding.spent)
    }

    @Test
    fun `empty operation allowance should skip source input preparation`() {
        val budget = SourceLpBudget(maxOperations = 0)
        var prepared = false

        val result = budget.runPrepared(Cancellation.Never, prepare = {
            prepared = true
            SourceLpInput(emptyList(), 0, emptyList())
        }) { true }

        assertNull(result)
        assertFalse(prepared)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `cancelled source input preparation should spend an operation without entering the solver`() {
        val budget = SourceLpBudget()
        var cancelled = false
        var entered = false

        val result = budget.runPrepared(Cancellation { cancelled }, prepare = {
            cancelled = true
            SourceLpInput(emptyList(), 0, emptyList())
        }) {
            entered = true
            true
        }

        assertNull(result)
        assertFalse(entered)
        assertEquals(1, budget.operations)
    }

    @Test
    fun `operation token should use the caller deadline unless an active cap ends sooner`() {
        val rows = listOf(exactColumnUpper(0, BigFraction.ONE))
        val caller = TimeSource.Monotonic.markNow() + 1.hours
        val cases = listOf(SourceLpBudget() to true, SourceLpBudget(maxActiveNanos = 1_000_000_000L) to false)
        for ((budget, callerBinds) in cases) {
            val stated = assertNotNull(budget.run(rows, 1, Cancellation.until(caller)) { it.deadline() })

            assertEquals(callerBinds, stated == caller)
            assertTrue(stated <= caller)
        }
    }

    @Test
    fun `equality subset should keep paired rows and omit unequal and unpaired bounds`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(2L)), BigFraction.ofLong(2L), strict),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(-3L)), BigFraction.ofLong(-3L)),
                exactColumnUpper(0, BigFraction.ofLong(2L)),
                exactColumnUpper(1, BigFraction.ONE),
            )

            val subset = assertNotNull(sourceEqualityRows(rows, 2, SourceLpBudget(), Cancellation.Never))

            assertEquals(2, subset.size)
            assertSame(rows[0], subset[0])
            assertSame(rows[1], subset[1])
        }
    }

    @Test
    fun `subset witness should not satisfy an omitted source constraint`() {
        val rows = listOf(
            exactColumnUpper(0, BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(), emptyList(), BigFraction.MINUS_ONE),
        )

        val subset = assertNotNull(sourceEqualityRows(rows, 1, SourceLpBudget(), Cancellation.Never))

        assertEquals(2, subset.size)
        assertTrue(listOf(BigFraction.ZERO).satisfiesSourceRows(subset))
        assertFalse(listOf(BigFraction.ZERO).satisfiesSourceRows(rows))
    }

    @Test
    fun `unequal opposing rows should not become an equality subset`() {
        val rows = listOf(exactColumnUpper(0, BigFraction.ONE), exactColumnLower(0, BigFraction.ZERO))

        assertTrue(assertNotNull(sourceEqualityRows(rows, 1, SourceLpBudget(), Cancellation.Never)).isEmpty())
    }

    @Test
    fun `equality matching spending should survive admission exhaustion and cancellation`() {
        for (cancel in listOf(false, true)) {
            var cancelled = false
            val rows = listOf(exactColumnUpper(0, BigFraction.ONE), exactColumnLower(0, BigFraction.ONE))
            val budget = SourceLpBudget(maxOperations = if (cancel) 4 else 1, onWork = { cancelled = cancel })
            val token = Cancellation { cancelled }
            val subset = assertNotNull(sourceEqualityRows(rows, 1, budget, token))
            val spent = budget.reservedWork
            val allocated = budget.reservedAllocation

            assertIs<ExactDoubleBoundedSplit.Unknown>(sourceDoubleBoundedSplit(subset, 1, budget, token))

            assertEquals(1, budget.operations)
            assertEquals(spent, budget.reservedWork)
            assertEquals(allocated, budget.reservedAllocation)
            assertTrue(spent > 0L)
            assertTrue(allocated > 0L)
        }
    }

    @Test
    fun `crossed opposite bounds should not publish an unexplained contradiction`() {
        val rows = listOf(exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ONE))

        assertIs<ExactDoubleBoundedSplit.Unknown>(
            sourceDoubleBoundedSplit(rows, 1, SourceLpBudget(), Cancellation.Never),
        )
    }

    @Test
    fun `constant source rows should retain strictness and exact zero activity`() {
        for (strict in listOf(false, true)) {
            val row = ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ZERO), BigFraction.ZERO, strict)

            val split = assertIs<ExactDoubleBoundedSplit.Split>(
                sourceDoubleBoundedSplit(listOf(row), 1, SourceLpBudget(), Cancellation.Never),
            )

            assertSame(row, split.bounded.single().inequality)
            assertEquals(BigFraction.ZERO, split.bounded.single().lower)
        }
    }

    @Test
    fun `failed and cancelled source operations should report spending once`() {
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
    fun `source row mutation should not alter an admitted owner`() {
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
    fun `repeated branch forms should reuse definitions without retaining thresholds`() {
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
    fun `source family reservations should survive replacement owners`() {
        val budget = SourceLpBudget(maxOperations = 1)
        SourceLp(emptyList(), 1, budget).use { assertNotNull(it.solve(Cancellation.Never)) }

        SourceLp(emptyList(), 1, budget).use { assertNull(it.solve(Cancellation.Never)) }

        assertEquals(1, budget.operations)
        assertTrue(budget.reservedWork > 0L)
        assertTrue(budget.reservedAllocation > 0L)
    }

    @Test
    fun `closing every owner should preserve primary and suppressed failures`() {
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
