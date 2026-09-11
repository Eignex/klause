package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpExactContinuationTest {
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
    fun `real scoped work stop supplies a current target for exact recovery`() {
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
        LpScopedSolver(state, workLimit = 1L).use { owner ->
            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ONE, assertNotNull(result.witness).primal[0])
            assertTrue(assertNotNull(result.continuation).eligible)
            assertNull(result.float)
        }
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

            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.witness)
            assertTrue(assertNotNull(result.continuation).success)
        }
    }

    @Test
    fun `verification exhaustion remains distinct from infeasibility`() {
        val model = LpBuilder().apply {
            addVar(0L, 2L)
            addRow(intArrayOf(0), longArrayOf(3L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        val complete = continueExactLp(model, basis)
        val beforeVerification = complete.metrics.workByPhase.filterKeys {
            it != ContinuationPhase.VERIFY && it != ContinuationPhase.ADMISSION
        }.values.sum()

        val limited = continueExactLp(model, basis, limits = ExactContinuationLimits(maxWork = beforeVerification + 1L))

        assertNotNull(complete.witness)
        assertEquals(ContinuationDecline.WORK, limited.metrics.decline)
        assertEquals(ContinuationPhase.VERIFY, limited.metrics.phase)
        assertNull(limited.witness)
        assertNull(limited.conflict)
    }

    @Test
    fun `source root cost and target status changes invalidate exact progress`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val first = LpExactState(source)
        val cache = LpExactContinuationCache()
        val lower = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        val upper = Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER))
        continueExactLp(assertNotNull(first.toWorkingModel()), lower, cache)

        val statusChanged = continueExactLp(assertNotNull(first.toWorkingModel()), upper, cache)
        val rootChanged = continueExactLp(assertNotNull(LpExactState(source).toWorkingModel()), upper, cache)
        val legacy = LpBuilder().apply { addVar(0L, 2L, cost = 1L) }.build(Sense.MINIMIZE)
        continueExactLp(legacy, lower, cache)
        legacy.cost[0] = 2L
        val costChanged = continueExactLp(legacy, lower, cache)

        assertTrue(statusChanged.metrics.invalidated)
        assertTrue(rootChanged.metrics.invalidated)
        assertTrue(costChanged.metrics.invalidated)
        assertEquals(BigFraction.ofLong(2L), assertNotNull(statusChanged.witness).primal[0])
    }

    @Test
    fun `singular notification preserves the copied target for logical repair`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val column = listOf(ExactLpEntry(0, one), ExactLpEntry(1, one))
        val fixed = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(column, column),
            listOf(one, one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds()), fixed, fixed),
            listOf(ExactLpRow(), ExactLpRow()),
            ExactLpObjective(List(4) { zero }),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED))
        var available = true
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) = if (available) basis else null
            override fun rejectSingularBasis(model: LpModel, basis: Basis): Boolean {
                available = false
                return true
            }
        }
        val hint = FloatLpResult(basis, 0.0, DoubleArray(2), DoubleArray(2) { Double.NaN }, exactState = state)

        val result = certifyLpResult(model, solver, hint)

        assertTrue(!available)
        assertEquals(BigFraction.ONE, assertNotNull(result.witness).primal.reduce { a, b -> a + b })
        assertEquals(1, assertNotNull(result.continuation).repairs)
    }

    @Test
    fun `either conflict policy can withhold continuation acceptance`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(zero),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) =
                Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        }
        for (rejected in listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_FARKAS)) {
            val result = certifyLpResult(
                model,
                solver,
                null,
                policy = LpCertificationPolicy { route, success ->
                    success && route != rejected
                },
            )

            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.rationalConflict)
            assertTrue(assertNotNull(result.continuation).success)
        }
    }

    @Test
    fun `unkeyable legacy authority cannot silently replenish a retained session`() {
        val model = LpBuilder().apply { repeat(300) { addVar(0L, 1L) } }.build(Sense.MINIMIZE)
        val cache = LpExactContinuationCache()
        val basis = Basis(intArrayOf(), Array(300) { VarStatus.AT_LOWER })
        assertNull(exactLpStateKey(model))

        val first = continueExactLp(model, basis, cache)
        val repeated = continueExactLp(model, basis, cache)

        assertNotNull(first.witness)
        assertEquals(ContinuationDecline.RESUME_KEY, repeated.metrics.decline)
        assertEquals(0, repeated.metrics.builds)
        assertNull(repeated.witness)
    }

    @Test
    fun `strict migration cannot reauthorize a withheld continuation witness`() {
        val model = LpBuilder().apply {
            val x = addRealVar(1.0, 2.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 0.0, strict = true)
        }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) =
                Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
        }
        for (withheld in listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_BASIS)) {
            var rationalCalls = 0
            val result = certifyLpResult(
                model,
                solver,
                null,
                policy = LpCertificationPolicy { route, success ->
                    if (route == LpCertifier.RATIONAL) rationalCalls++
                    success && route != withheld
                },
            )

            assertTrue(assertNotNull(result.continuation).success)
            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.witness)
            assertEquals(1, rationalCalls)
        }
    }

    @Test
    fun `strict migration cannot reauthorize a withheld continuation conflict`() {
        val model = LpBuilder().apply {
            val x = addRealVar(2.0, 3.0)
            addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.LE, 1.0, strict = true)
        }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) =
                Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
        }
        for (withheld in listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_FARKAS)) {
            var rationalCalls = 0
            val result = certifyLpResult(
                model,
                solver,
                null,
                policy = LpCertificationPolicy { route, success ->
                    if (route == LpCertifier.RATIONAL) rationalCalls++
                    success && route != withheld
                },
            )

            assertTrue(assertNotNull(result.continuation).success)
            assertEquals(LpVerdict.INDETERMINATE, result.verdict)
            assertNull(result.rationalConflict)
            assertEquals(1, rationalCalls)
        }
    }

    @Test
    fun `new identities receive effort after the previous session is exhausted`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(2L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val lower = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        val upper = Basis(intArrayOf(), arrayOf(VarStatus.AT_UPPER))
        val limits = ExactContinuationLimits(maxWork = 10000L)
        for (native in listOf(true, false)) {
            val model = if (native) {
                assertNotNull(LpExactState(source).toWorkingModel())
            } else {
                LpBuilder().apply { addVar(0L, 2L) }.build(Sense.MINIMIZE)
            }
            val cache = LpExactContinuationCache()
            var result = continueExactLp(model, lower, cache, limits = limits)
            repeat(64) {
                if (result.metrics.decline != ContinuationDecline.WORK) {
                    result = continueExactLp(model, lower, cache, limits = limits)
                }
            }
            assertEquals(ContinuationDecline.WORK, result.metrics.decline)
            repeat(2) {
                val exhausted = continueExactLp(model, lower, cache, limits = limits)
                assertEquals(ContinuationDecline.WORK, exhausted.metrics.decline)
                assertEquals(0, exhausted.metrics.builds)
                assertNull(exhausted.witness)
                assertTrue(cache.usedWork <= limits.maxWork)
            }
            if (!native) model.cost[0] = 1L

            val changed = continueExactLp(model, if (native) upper else lower, cache, limits = limits)

            assertTrue(changed.metrics.invalidated)
            assertNotNull(changed.witness)
            assertEquals(1, changed.metrics.builds)
        }
    }

    @Test
    fun `admission rejects cancellation dimensions and large bounds before exporting a target`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.ofIeee(1e308))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        var exports = 0
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel): Basis? {
                exports++
                return null
            }
        }

        val bits = captureContinuationTarget(
            model,
            solver,
            null,
            ExactContinuationLimits(maxBits = 64),
            Cancellation.Never,
        )
        val dimension = captureContinuationTarget(
            model,
            solver,
            null,
            ExactContinuationLimits(maxColumns = 0),
            Cancellation.Never,
        )
        val cancelled = captureContinuationTarget(model, solver, null, ExactContinuationLimits(), Cancellation { true })

        assertEquals(ContinuationDecline.BITS, bits.metrics.decline)
        assertEquals(ContinuationDecline.DIMENSION, dimension.metrics.decline)
        assertEquals(ContinuationDecline.CANCELLED, cancelled.metrics.decline)
        assertEquals(0, exports)
        for (declined in listOf(bits, dimension, cancelled)) {
            assertEquals(ContinuationPhase.ADMISSION, declined.metrics.phase)
        }
    }

    @Test
    fun `native source recovers the nonfinite adjusted right hand side`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(2L)))),
            listOf(ExactLpNumber.of(1L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.ofIeee(1e308)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
        )
        LpScopedSolver(LpExactState(source)).use { owner ->
            val result = assertNotNull(owner.solve())

            assertNull(result.float)
            assertEquals(LpVerdict.FEASIBLE, result.verdict)
            val point = assertNotNull(result.witness).primal.single()
            assertTrue(point >= BigFraction.ZERO && point + point <= BigFraction.ONE)
            assertTrue(assertNotNull(result.continuation).success)
        }
    }
}
