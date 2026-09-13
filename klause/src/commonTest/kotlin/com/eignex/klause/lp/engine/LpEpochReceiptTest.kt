package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class LpEpochReceiptTest {
    @Test
    fun `refinement spending is carried without inventing continuation spending`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        LpScopedSolver(state).use { donor ->
            donor.refinementCache.work = Long.MAX_VALUE
            donor.refinementCache.allocation = 1234L
            donor.refinementCache.pivots = Int.MAX_VALUE
            donor.refinementCache.elapsed = 3.seconds + 17.nanoseconds
            donor.refinementCache.attempted = state
            donor.refinementCache.lastLimits = LpRefinementLimits(maxWork = 1L)
            val receipt = assertNotNull(donor.exportEpochReceipt())
            assertNull(receipt.continuation)

            LpScopedSolver(LpExactState(state.model)).use { replacement ->
                assertTrue(replacement.importEpochReceipt(receipt))

                assertEquals(receipt.refinement, assertNotNull(replacement.exportEpochReceipt()).refinement)
                assertNull(replacement.exportEpochBudget())
                assertSame(replacement.state, replacement.refinementCache.attempted)
                assertFalse(donor.refinementCache === replacement.refinementCache)
                assertEquals(LpRefinementMetrics(), replacement.refinementCache.lastMetrics)
            }
            assertEquals(receipt, donor.exportEpochReceipt())
        }
    }

    @Test
    fun `both ledgers survive repeated equivalent replacements`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val original = LpScopedSolver(state).use { donor ->
            assertTrue(donor.importEpochBudget(LpEpochBudget(state.model, 10L, 20L, 30L, 4, 5, 60)))
            donor.refinementCache.work = 70L
            donor.refinementCache.allocation = 80L
            donor.refinementCache.pivots = 9
            donor.refinementCache.elapsed = 11.nanoseconds
            donor.refinementCache.attempted = state
            donor.refinementCache.lastLimits = LpRefinementLimits()
            assertNotNull(donor.exportEpochReceipt())
        }
        var carried = original

        repeat(3) {
            LpScopedSolver(LpExactState(state.model)).use { replacement ->
                assertTrue(replacement.importEpochReceipt(carried))
                carried = assertNotNull(replacement.exportEpochReceipt())

                assertEquals(original.refinement, carried.refinement)
                assertEquals(original.continuation?.copy(authority = replacement.state.model), carried.continuation)
                assertSame(replacement.state, replacement.refinementCache.attempted)
                assertFalse(replacement.importEpochReceipt(carried))
                assertFalse(replacement.importEpochBudget(assertNotNull(carried.continuation)))
            }
        }
    }

    @Test
    fun `current attempts remain suppressed while changed limits retain spent work`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val limits = LpRefinementLimits(maxWork = 7L)
        val receipt = LpScopedSolver(state).use { donor ->
            donor.refinementCache.work = 7L
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(donor, donor.refinementCache, limits),
            )
            assertEquals(LpRefinementDecline.WORK, result.metrics.decline)
            assertNotNull(donor.exportEpochReceipt())
        }
        LpScopedSolver(LpExactState(state.model)).use { replacement ->
            assertTrue(replacement.importEpochReceipt(receipt))
            val working = assertNotNull(replacement.state.toWorkingModel())

            val repeated = refineLp(working, LpRefinementRequest(replacement, replacement.refinementCache, limits))
            val changed = refineLp(
                working,
                LpRefinementRequest(replacement, replacement.refinementCache, limits.copy(maxWork = 6L)),
            )

            assertEquals(LpRefinementDecline.REPEATED, repeated.metrics.decline)
            assertEquals(LpRefinementDecline.WORK, changed.metrics.decline)
            assertEquals(receipt.refinement.work, replacement.refinementCache.work)
            assertTrue(replacement.refinementCache.elapsed >= receipt.refinement.elapsed)
        }
    }

    @Test
    fun `a historical attempt does not suppress the replacement current state`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val limits = LpRefinementLimits(maxWork = 7L)
        val receipt = LpScopedSolver(state).use { donor ->
            donor.refinementCache.work = 7L
            donor.refinementCache.attempted = LpExactState(state.model)
            donor.refinementCache.lastLimits = limits
            assertNotNull(donor.exportEpochReceipt())
        }
        assertFalse(receipt.refinement.attemptedCurrent)
        LpScopedSolver(LpExactState(state.model)).use { replacement ->
            assertTrue(replacement.importEpochReceipt(receipt))
            assertNull(replacement.refinementCache.attempted)
            val result = refineLp(
                assertNotNull(replacement.state.toWorkingModel()),
                LpRefinementRequest(replacement, replacement.refinementCache, limits),
            )

            assertEquals(LpRefinementDecline.WORK, result.metrics.decline)
            assertSame(replacement.state, replacement.refinementCache.attempted)
            assertEquals(7L, replacement.refinementCache.work)
        }
    }

    @Test
    fun `authority declines leave both destination ledgers untouched`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 3)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val foreign = state.model.copy(objective = ExactLpObjective(List(2) { ExactLpNumber.of(1L) }))
        val receipt = LpScopedSolver(state).use { donor ->
            assertTrue(donor.importEpochBudget(LpEpochBudget(state.model, 10L, 20L, 30L, 4, 5, 60)))
            donor.refinementCache.work = 70L
            assertNotNull(donor.exportEpochReceipt())
        }
        val invalid = listOf(
            receipt.copy(authority = foreign),
            receipt.copy(rows = LpScopedRows(listOf(LpRowIdentity(1L, null)), 1L)),
            receipt.copy(continuation = assertNotNull(receipt.continuation).copy(authority = foreign)),
        )

        for (candidate in invalid) {
            LpScopedSolver(LpExactState(state.model)).use { replacement ->
                val before = replacement.exportEpochReceipt()
                assertFalse(replacement.importEpochReceipt(candidate))
                assertEquals(before, replacement.exportEpochReceipt())
                assertTrue(replacement.importEpochReceipt(receipt))
            }
        }
    }

    @Test
    fun `direct refinement activity makes a destination ineligible for receipt import`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val receipt = LpScopedSolver(state).use { assertNotNull(it.exportEpochReceipt()) }

        for (field in listOf("work", "allocation", "pivots", "elapsed", "attempt", "limits")) {
            LpScopedSolver(LpExactState(state.model)).use { replacement ->
                val cache = replacement.refinementCache
                when (field) {
                    "work" -> cache.work = 1L

                    "allocation" -> cache.allocation = 1L

                    "pivots" -> cache.pivots = 1

                    "elapsed" -> cache.elapsed = 1.nanoseconds

                    "attempt" -> {
                        cache.attempted = replacement.state
                        cache.lastLimits = LpRefinementLimits()
                    }

                    "limits" -> cache.lastLimits = LpRefinementLimits()
                }
                val before = replacement.exportEpochReceipt()

                assertFalse(replacement.importEpochReceipt(receipt), field)
                assertEquals(before, replacement.exportEpochReceipt(), field)
            }
        }
    }

    @Test
    fun `empty receipt imports are one shot across both budget APIs`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val receipt = LpScopedSolver(state).use { assertNotNull(it.exportEpochReceipt()) }
        val continuation = LpEpochBudget(state.model, 0L, 0L, 0L, 0, 0, 0)

        for (aggregateFirst in listOf(false, true)) {
            LpScopedSolver(LpExactState(state.model)).use { replacement ->
                if (aggregateFirst) {
                    assertTrue(replacement.importEpochReceipt(receipt))
                } else {
                    assertTrue(replacement.importEpochBudget(continuation))
                }

                assertFalse(replacement.importEpochReceipt(receipt))
                assertFalse(replacement.importEpochBudget(continuation))
            }
        }
    }

    @Test
    fun `receipt import requires root depth without consuming a declined import`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val receipt = LpScopedSolver(state).use { assertNotNull(it.exportEpochReceipt()) }
        LpScopedSolver(LpExactState(state.model)).use { replacement ->
            assertTrue(replacement.push())
            val before = replacement.exportEpochReceipt()

            assertFalse(replacement.importEpochReceipt(receipt))
            assertEquals(before, replacement.exportEpochReceipt())
            assertTrue(replacement.pop(0))
            assertTrue(replacement.importEpochReceipt(receipt))
        }
    }

    @Test
    fun `exact certification makes a destination ineligible even without refinement`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val receipt = LpScopedSolver(state).use { assertNotNull(it.exportEpochReceipt()) }
        LpScopedSolver(LpExactState(state.model)).use { replacement ->
            val result = assertNotNull(replacement.solve())
            assertNotNull(result.exactPrimal)
            assertNull(result.refinement)
            assertTrue(replacement.refinementCache.pristineForEpoch())
            val before = replacement.exportEpochReceipt()

            assertFalse(replacement.importEpochReceipt(receipt))
            assertEquals(before, replacement.exportEpochReceipt())
        }
    }

    @Test
    fun `closed and working active owners cannot export or import receipts`() {
        val builder = LpBuilder()
        builder.addVar(0, 4)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val owner = LpScopedSolver(state)
        val receipt = assertNotNull(owner.exportEpochReceipt())
        owner.withWorkingModel(LpWorkingModel.overrides(state), allowance = LpFloatAllowance(10000L, 10)) {
            assertFailsWith<IllegalStateException> { owner.exportEpochReceipt() }
            assertFailsWith<IllegalStateException> { owner.importEpochReceipt(receipt) }
        }
        assertEquals(receipt, owner.exportEpochReceipt())
        owner.close()

        assertNull(owner.exportEpochReceipt())
        assertFalse(owner.importEpochReceipt(receipt))
        assertFalse(owner.prepare(Cancellation.Never))
    }
}
