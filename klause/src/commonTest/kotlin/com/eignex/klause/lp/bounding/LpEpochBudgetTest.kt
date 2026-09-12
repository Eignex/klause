package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpEpochBudget
import com.eignex.klause.lp.engine.LpExactContinuationCache
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.continueExactLp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStatus
import com.eignex.klause.simplex.exact.ExactContinuation
import com.eignex.klause.simplex.exact.ExactContinuationInput
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpEpochBudgetTest {
    @Test
    fun `equivalent replacement cannot replenish exhausted feasibility pivots`() {
        val source = assertNotNull(
            LpBuilder().apply {
                repeat(4) { j ->
                    addVar(0L, 10L)
                    addRow(intArrayOf(j), longArrayOf(3L), Relation.GE, 1L)
                }
            }.build(Sense.MINIMIZE).trailModel(),
        )
        val state = LpExactState(source)
        val basis = Basis(IntArray(4) { 4 + it }, Array(8) { if (it < 4) VarStatus.AT_LOWER else VarStatus.BASIC })
        val donor = LpExactContinuationCache()
        val limited = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            basis,
            donor,
            limits = ExactContinuationLimits(maxPivots = 2),
        )
        val budget = assertNotNull(donor.exportBudget(state))
        val replacement = LpExactState(state.model)
        val cache = LpExactContinuationCache()
        assertTrue(cache.importBudget(replacement, budget))

        val resumed = continueExactLp(
            assertNotNull(replacement.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxPivots = 2),
        )

        assertEquals(ContinuationDecline.PIVOTS, limited.metrics.decline)
        assertEquals(2, budget.pivots)
        assertEquals(ContinuationDecline.PIVOTS, resumed.metrics.decline)
        assertEquals(0, resumed.metrics.pivots)
        assertNull(resumed.witness)
        assertEquals(budget, donor.exportBudget(state))
        assertTrue(cache.usedWork > budget.work)
    }

    @Test
    fun `imported work remains spent across an equivalent basis change`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).trailModel())
        val state = LpExactState(source)
        val cache = LpExactContinuationCache()
        assertTrue(cache.importBudget(state, LpEpochBudget(state.model, 100L, 0L, 0L, 0, 0, 0)))
        val limits = ExactContinuationLimits(maxWork = 100L)
        val working = assertNotNull(state.toWorkingModel())
        continueExactLp(working, Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)), cache, limits = limits)

        val changed = continueExactLp(working, Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER)), cache, limits = limits)

        assertEquals(ContinuationDecline.WORK, changed.metrics.decline)
        assertNull(changed.witness)
        assertEquals(100L, cache.usedWork)
    }

    @Test
    fun `a changed exact authority starts an unrelated budget`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).trailModel())
        val state = LpExactState(source)
        val cache = LpExactContinuationCache()
        assertTrue(cache.importBudget(state, LpEpochBudget(state.model, 100L, 0L, 0L, 0, 0, 500)))
        val changed = LpExactState(
            source.copy(
                columns = listOf(
                    source.column(
                        0,
                    ).copy(
                        bounds = ExactLpBounds(ExactLpSide(ExactLpNumber.of(1L)), ExactLpSide(ExactLpNumber.of(10L))),
                    ),
                ),
            ),
        )

        val result = continueExactLp(
            assertNotNull(changed.toWorkingModel()),
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            cache,
            limits = ExactContinuationLimits(maxBits = 24),
        )

        assertNotNull(result.witness)
        assertTrue(cache.scalarPeak < 500)
    }

    @Test
    fun `owner import is one shot and requires exact authority`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).trailModel())
        val state = LpExactState(source)
        val budget = LpEpochBudget(state.model, 100L, 50L, 10L, 2, 3, 40)
        LpScopedSolver(LpExactState(source)).use { owner ->
            assertTrue(owner.importEpochBudget(budget))
            assertFalse(owner.importEpochBudget(budget))
            assertEquals(budget.copy(authority = owner.state.model), owner.exportEpochBudget())
        }
        val changed = source.copy(objective = ExactLpObjective(listOf(ExactLpNumber.of(1L))))
        LpScopedSolver(LpExactState(changed)).use { owner ->
            assertFalse(owner.importEpochBudget(budget))
            assertNull(owner.exportEpochBudget())
        }
    }

    @Test
    fun `cancellation before lane publication retains admitted input peak`() {
        val large = BigFraction.of(BigInteger.ONE shl 40, BigInteger.ONE)
        val session = ExactContinuation(
            ExactContinuationInput(
                listOf(emptyList()),
                emptyList(),
                listOf(large),
                listOf(null),
                emptyList(),
                listOf(ContinuationStatus.LOWER),
            ),
        )

        val cancelled = session.resume(cancellation = Cancellation { session.normalizedScalarPeak >= 41 })
        val lowered = session.resume(ExactContinuationLimits(maxBits = 24))

        assertEquals(ContinuationDecline.CANCELLED, cancelled.metrics.decline)
        assertEquals(1, cancelled.metrics.builds)
        assertEquals(41, session.normalizedScalarPeak)
        assertEquals(ContinuationDecline.BITS, lowered.metrics.decline)
        assertNull(lowered.values)
    }

    @Test
    fun `precision restart peak survives owner transfer and a lower scalar ceiling`() {
        val huge = ExactLpNumber.of(BigFraction.of(BigInteger.ONE shl 100, BigInteger.ONE))
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, huge), ExactLpEntry(1, one)),
                listOf(ExactLpEntry(0, one), ExactLpEntry(1, huge)),
            ),
            listOf(one, one),
            List(4) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), if (it < 2) null else ExactLpSide(zero))) },
            List(2) { ExactLpRow() },
            ExactLpObjective(List(4) { zero }),
        )
        val state = LpExactState(source)
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        val donor = LpExactContinuationCache()
        val first = continueExactLp(assertNotNull(state.toWorkingModel()), basis, donor)
        val budget = assertNotNull(donor.exportBudget(state))
        val replacement = LpExactState(state.model)
        val cache = LpExactContinuationCache()
        assertTrue(cache.importBudget(replacement, budget))

        val lowered = continueExactLp(
            assertNotNull(replacement.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxBits = budget.scalarPeak - 1),
        )
        val equal = continueExactLp(
            assertNotNull(replacement.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxBits = budget.scalarPeak),
        )

        assertNotNull(first.witness)
        assertTrue(first.metrics.restarts > 0)
        assertTrue(budget.scalarPeak > 101)
        assertEquals(ContinuationDecline.BITS, lowered.metrics.decline)
        assertNull(lowered.witness)
        assertNotNull(equal.witness)
    }

    @Test
    fun `near maximum costs remain exhausted through accounting and repeated transfer`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }.build(Sense.MINIMIZE).trailModel())
        val state = LpExactState(source)
        val cache = LpExactContinuationCache()
        val budget = LpEpochBudget(state.model, Long.MAX_VALUE - 2L, Long.MAX_VALUE - 2L, Long.MAX_VALUE - 2L, 0, 0, 0)
        assertTrue(cache.importBudget(state, budget))
        cache.accountInput(0L, 0L, 0L)
        assertEquals(budget, cache.exportBudget(state))

        cache.accountInput(10L, 10L, 10L)
        val carried = assertNotNull(cache.exportBudget(state))
        val replacement = LpExactContinuationCache()
        assertTrue(replacement.importBudget(state, carried))
        val declined = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            replacement,
        )

        assertNull(declined.witness)
        assertEquals(Long.MAX_VALUE, replacement.usedWork)
        assertEquals(Long.MAX_VALUE, replacement.usedAllocation)
        assertEquals(Long.MAX_VALUE, replacement.usedTimeNs)
        assertEquals(carried, replacement.exportBudget(state))
    }

    @Test
    fun `initialized continuation totals saturate without accepting negative cost`() {
        val continuation = ExactContinuation(
            ExactContinuationInput(
                listOf(emptyList()),
                emptyList(),
                listOf(BigFraction.ZERO),
                listOf(null),
                emptyList(),
                listOf(ContinuationStatus.LOWER),
            ),
        )
        continuation.account(Long.MAX_VALUE - 2L, Long.MAX_VALUE - 2L, Long.MAX_VALUE - 2L)

        continuation.account(10L, 10L, 10L)
        continuation.account(0L, 0L, 0L)
        val declined = continuation.resume()

        assertNull(declined.values)
        assertEquals(Long.MAX_VALUE, continuation.usedWork)
        assertEquals(Long.MAX_VALUE, continuation.usedAllocation)
        assertEquals(Long.MAX_VALUE, continuation.usedTimeNs)
        assertFailsWith<IllegalArgumentException> { continuation.account(-1L, 0L, 0L) }
    }
}
