package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationStatus
import com.eignex.klause.simplex.exact.ExactContinuation
import com.eignex.klause.simplex.exact.ExactContinuationInput
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpExactContinuationTest {
    @Test
    fun `repeated continuation cannot replenish exhausted feasibility pivots`() {
        val source = assertNotNull(
            LpBuilder().apply {
                repeat(4) { j ->
                    addVar(0L, 10L)
                    addRow(intArrayOf(j), longArrayOf(3L), Relation.GE, 1L)
                }
            }.build(Sense.MINIMIZE).authoritativeModel(),
        )
        val state = LpExactState(source)
        val basis = Basis(IntArray(4) { 4 + it }, Array(8) { if (it < 4) VarStatus.AT_LOWER else VarStatus.BASIC })
        val cache = LpExactContinuationCache()
        val limited = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxPivots = 2),
        )
        val spent = cache.usedWork

        val resumed = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxPivots = 2),
        )

        assertEquals(ContinuationDecline.PIVOTS, limited.metrics.decline)
        assertEquals(2, limited.metrics.pivots)
        assertEquals(ContinuationDecline.PIVOTS, resumed.metrics.decline)
        assertEquals(0, resumed.metrics.pivots)
        assertNull(resumed.witness)
        assertTrue(cache.usedWork > spent)
    }

    @Test
    fun `precision restart peak survives a lower scalar ceiling`() {
        val huge = ExactLpNumber.of(BigFraction.of(BIG_ONE shl 100, BIG_ONE))
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
        val cache = LpExactContinuationCache()
        val first = continueExactLp(assertNotNull(state.toWorkingModel()), basis, cache)
        val peak = cache.scalarPeak

        val lowered = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxBits = peak - 1),
        )
        val equal = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            basis,
            cache,
            limits = ExactContinuationLimits(maxBits = peak),
        )

        assertNotNull(first.witness)
        assertTrue(first.metrics.restarts > 0)
        assertTrue(peak > 101)
        assertEquals(ContinuationDecline.BITS, lowered.metrics.decline)
        assertNull(lowered.witness)
        assertNotNull(equal.witness)
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

    @Test
    fun `source verification resumes a previously limited import`() {
        val model = LpBuilder().apply {
            repeat(4) { j ->
                addVar(0L, 10L)
                addRow(intArrayOf(j), longArrayOf(3L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)
        val basis = Basis(IntArray(4) { 4 + it }, Array(8) { if (it < 4) VarStatus.AT_LOWER else VarStatus.BASIC })
        val cache = LpExactContinuationCache()

        val short = continueExactLp(model, basis, cache, limits = ExactContinuationLimits(maxPivots = 2))
        val full = continueExactLp(model, basis, cache, limits = ExactContinuationLimits(maxPivots = 4))

        assertEquals(ContinuationDecline.PIVOTS, short.metrics.decline)
        assertNull(short.witness)
        assertEquals(0, full.metrics.builds)
        assertEquals(2, full.metrics.pivots)
        assertEquals(List(4) { BigFraction.ofLong(3).reciprocal() }, assertNotNull(full.witness).primal)
        assertEquals(full.metrics.work, full.metrics.workByPhase.values.sum())
    }

    @Test
    fun `mutable source edits invalidate a retained candidate`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
        val cache = LpExactContinuationCache()
        val first = continueExactLp(model, basis, cache)
        model.rhs[0] = -2L

        val changed = continueExactLp(model, basis, cache)

        assertEquals(BigFraction.ONE, assertNotNull(first.witness).primal[0])
        assertEquals(BigFraction.ofLong(2), assertNotNull(changed.witness).primal[0])
        assertTrue(changed.metrics.invalidated)
        assertEquals(1, changed.metrics.builds)
    }

    @Test
    fun `native exact conflict retains all original bound witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(9)))),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 11L))
        assertTrue(trail.assertBound(1, false, ExactLpSide(zero), 12L))
        val state = trail.state
        val working = assertNotNull(state.toWorkingModel())
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        val result = continueExactLp(working, basis)
        trail.push()
        trail.pop(0)

        assertNotNull(result.conflict)
        val support = assertNotNull(result.support)
        assertTrue(support.state === state)
        assertEquals(setOf(11L, 12L), support.sides.map { it.witness }.toSet())
        assertEquals(listOf(9), assertNotNull(support.rows.single().second.premises).literalEntries())
    }

    @Test
    fun `strict closure is not a source witness`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())

        val result = continueExactLp(model, Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)))

        assertNull(result.witness)
        assertNull(result.conflict)
        assertEquals(ContinuationDecline.CANDIDATE, result.metrics.decline)
    }

    @Test
    fun `missing basis and cancelled source import decline separately`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)

        val missing = continueExactLp(model, null)
        val cancelled = continueExactLp(
            model,
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
            cancellation = Cancellation { true },
        )

        assertEquals(ContinuationDecline.NO_BASIS, missing.metrics.decline)
        assertEquals(ContinuationDecline.CANCELLED, cancelled.metrics.decline)
        assertNull(missing.witness)
        assertNull(cancelled.witness)
    }

    @Test
    fun `either witness policy can withhold continuation acceptance`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) =
                Basis(intArrayOf(1), arrayOf(VarStatus.FREE, VarStatus.BASIC))
        }
        for (rejected in listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_BASIS)) {
            val result = certifyLpResult(
                model,
                solver,
                null,
                policy = LpCertificationPolicy { route, success ->
                    success && route != rejected
                },
            )

            assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
            assertEquals(BigFraction.ZERO, result.lowerBound)
            assertTrue(assertNotNull(assertNotNull(result.bound).support).state === state)
            assertNull(result.witness)
            assertTrue(assertNotNull(result.continuation).success)
        }
    }

}
