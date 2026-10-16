package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpExactContinuationTest {
    @Test
    fun `implicit satisfaction should verify all source coordinates and origins`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val two = ExactLpNumber.of(2L)
        val source = ExactLpModel(
            listOf(emptyList(), listOf(ExactLpEntry(0, one)), emptyList()),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(two), ExactLpSide(two)), origin = ExactLpNumber.of(10L)),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))),
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(two, strict = true), ExactLpSide(ExactLpNumber.of(3L))),
                    origin = ExactLpNumber.of(20L),
                ),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(4) { zero }),
        )

        val result = continueExactLp(
            assertNotNull(LpExactState(source).toWorkingModel()),
            Basis(intArrayOf(3), arrayOf(VarStatus.FIXED, VarStatus.AT_LOWER, VarStatus.AT_LOWER, VarStatus.BASIC)),
            limits = ExactContinuationLimits(maxCells = 4),
        )

        val point = assertNotNull(result.witness).primal
        assertEquals(3, point.size)
        assertEquals(BigFraction.ofLong(12), point[0])
        assertEquals(BigFraction.ONE, point[1])
        assertTrue(point[2] > BigFraction.ofLong(22) && point[2] < BigFraction.ofLong(23))
        assertTrue(result.metrics.success)
        assertNull(result.conflict)
    }

    @Test
    fun `nonzero objective components should keep dense continuation admission`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val objectives = listOf(
            ExactLpObjective(listOf(one, zero, zero, zero)),
            ExactLpObjective(List(4) { zero }, constant = one),
            ExactLpObjective(List(4) { zero }, externalConstant = one),
        )
        for (objective in objectives) {
            val source = ExactLpModel(
                listOf(emptyList(), listOf(ExactLpEntry(0, one)), emptyList()),
                listOf(zero),
                List(4) { ExactLpColumn(ExactLpBounds()) },
                listOf(ExactLpRow()),
                objective,
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())
            val basis = Basis(intArrayOf(3), arrayOf(VarStatus.FREE, VarStatus.FREE, VarStatus.FREE, VarStatus.BASIC))

            val compact = continueExactLp(model, basis, limits = ExactContinuationLimits(maxCells = 4))
            val dense = continueExactLp(model, basis)

            assertEquals(ContinuationDecline.DIMENSION, compact.metrics.decline)
            assertEquals(0, compact.metrics.builds)
            assertNull(compact.witness)
            assertNotNull(dense.witness)
        }
    }

    @Test
    fun `a cost bearing empty column should retain original unboundedness verification`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList(), listOf(ExactLpEntry(0, ExactLpNumber.of(1L))), emptyList()),
            listOf(zero),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero, zero, zero)),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.toWorkingModel())
        val solver = object : LpSolver {
            override val solvedExactState = state
            override val infeasibleRay: DoubleArray? = null
            override val recessionDirection = doubleArrayOf(1.0, 0.0, 0.0)
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) =
                Basis(intArrayOf(3), arrayOf(VarStatus.AT_LOWER, VarStatus.FREE, VarStatus.FREE, VarStatus.BASIC))
        }

        val result = certifyLpResult(model, solver, null)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertEquals(
            listOf(BigFraction.ONE, BigFraction.ZERO, BigFraction.ZERO),
            assertNotNull(result.unboundedness).direction,
        )
    }

    @Test
    fun `implicit target admission should retain resource declines before basis export`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList(), listOf(ExactLpEntry(0, one)), emptyList()),
            listOf(zero),
            List(4) { ExactLpColumn(ExactLpBounds()) },
            listOf(ExactLpRow()),
            ExactLpObjective(List(4) { zero }, scale = ExactLpNumber.of(1L shl 25)),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        var exports = 0
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel): Basis {
                exports++
                return Basis(intArrayOf(3), arrayOf(VarStatus.FREE, VarStatus.FREE, VarStatus.FREE, VarStatus.BASIC))
            }
        }
        val cases = listOf(
            Triple(ExactContinuationLimits(maxCells = 4, maxWork = 2L), Cancellation.Never, ContinuationDecline.WORK),
            Triple(
                ExactContinuationLimits(maxCells = 4, maxAllocation = 0L),
                Cancellation.Never,
                ContinuationDecline.ALLOCATION,
            ),
            Triple(ExactContinuationLimits(maxCells = 4, maxBits = 24), Cancellation.Never, ContinuationDecline.BITS),
            Triple(ExactContinuationLimits(maxCells = 4, maxTimeNs = 0L), Cancellation.Never, ContinuationDecline.TIME),
            Triple(ExactContinuationLimits(maxCells = 4), Cancellation { true }, ContinuationDecline.CANCELLED),
        )
        for ((limits, token, reason) in cases) {
            val declined = captureContinuationTarget(model, solver, null, limits, token)

            assertEquals(reason, declined.metrics.decline)
            assertNull(declined.basis)
            assertEquals(declined.metrics.work, declined.metrics.workByPhase.values.sum())
            assertTrue(declined.metrics.work <= limits.maxWork && declined.metrics.allocation <= limits.maxAllocation)
        }
        assertEquals(0, exports)

        val target = captureContinuationTarget(
            model, solver, null, ExactContinuationLimits(maxCells = 4), Cancellation.Never,
        )

        assertNotNull(target.basis)
        assertNull(target.metrics.decline)
        assertEquals(1, exports)
    }

    @Test
    fun `appended source rows should give empty columns fresh physical authority`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val logical = ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))
        val source = ExactLpModel(
            listOf(emptyList(), listOf(ExactLpEntry(0, one)), emptyList()),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds()), logical,
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(4) { zero }),
        )
        val cache = LpExactContinuationCache()
        val first = continueExactLp(
            assertNotNull(LpExactState(source).toWorkingModel()),
            Basis(intArrayOf(3), arrayOf(VarStatus.FREE, VarStatus.FREE, VarStatus.FREE, VarStatus.BASIC)),
            cache,
            limits = ExactContinuationLimits(maxCells = 4),
        )
        val appended = source.appendScopedRow(LpScopedRow(1L, listOf(0 to one), ExactLpNumber.of(2L), logical))

        val changed = continueExactLp(
            assertNotNull(LpExactState(appended).toWorkingModel()),
            Basis(
                intArrayOf(3, 4),
                arrayOf(VarStatus.FREE, VarStatus.FREE, VarStatus.FREE, VarStatus.BASIC, VarStatus.BASIC),
            ),
            cache,
            limits = ExactContinuationLimits(maxCells = 14),
        )

        assertEquals(BigFraction.ZERO, assertNotNull(first.witness).primal[0])
        assertEquals(
            listOf(BigFraction.ofLong(2), BigFraction.ONE, BigFraction.ZERO),
            assertNotNull(changed.witness).primal,
        )
        assertTrue(changed.metrics.invalidated)
        assertEquals(1, changed.metrics.builds)
    }

    @Test
    fun `implicit column bound changes should invalidate the retained source point`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList(), listOf(ExactLpEntry(0, ExactLpNumber.of(1L))), emptyList()),
            listOf(ExactLpNumber.of(1L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(3L)))),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(List(4) { zero }),
        )
        val trail = LpBoundTrail(source)
        val cache = LpExactContinuationCache()
        val basis = Basis(intArrayOf(3), arrayOf(VarStatus.AT_LOWER, VarStatus.FREE, VarStatus.FREE, VarStatus.BASIC))
        val limits = ExactContinuationLimits(maxCells = 4)
        val first = continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache, limits = limits)
        trail.push()
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 11L))

        val changed = continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache, limits = limits)

        assertEquals(BigFraction.ZERO, assertNotNull(first.witness).primal[0])
        assertEquals(BigFraction.ofLong(2), assertNotNull(changed.witness).primal[0])
        assertTrue(changed.metrics.invalidated)
    }

    @Test
    fun `direct continuation dimension declines should retain no state and later admit another model`() {
        val cache = LpExactContinuationCache()
        val models = listOf(2, 1).map { rows ->
            LpBuilder().apply {
                addVar(0L, 1L)
                repeat(rows) { addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L) }
            }.build(Sense.MINIMIZE)
        }
        val largeBasis = Basis(intArrayOf(0, 2), arrayOf(VarStatus.BASIC, VarStatus.FIXED, VarStatus.BASIC))
        val limits = ExactContinuationLimits(maxRows = 1)
        repeat(2) {
            val declined = continueExactLp(models[0], largeBasis, cache, limits = limits)
            assertEquals(ContinuationDecline.DIMENSION, declined.metrics.decline)
            assertEquals(1L, declined.metrics.work)
            assertEquals(0, declined.metrics.builds)
            assertNull(declined.witness)
        }

        val admitted = continueExactLp(
            models[1],
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
            cache,
            limits = limits,
        )

        assertEquals(listOf(BigFraction.ONE), assertNotNull(admitted.witness).primal)
        assertNull(admitted.metrics.decline)
        assertEquals(1, admitted.metrics.builds)
    }

    @Test
    fun `structural target declines should skip basis export and admit a retry under current limits`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        var exports = 0
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel): Basis {
                exports++
                return Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
            }
        }
        val limits = listOf(
            ExactContinuationLimits(maxRows = 0),
            ExactContinuationLimits(maxColumns = 1),
            ExactContinuationLimits(maxCells = 3),
        )
        for (limit in limits) {
            repeat(2) {
                val declined = captureContinuationTarget(model, solver, null, limit, Cancellation.Never)
                assertEquals(ContinuationDecline.DIMENSION, declined.metrics.decline)
                assertEquals(1L, declined.metrics.work)
                assertEquals(0L, declined.metrics.allocation)
                assertNull(declined.basis)
            }
        }
        assertEquals(0, exports)

        val admitted = captureContinuationTarget(model, solver, null, ExactContinuationLimits(), Cancellation.Never)

        assertNotNull(admitted.basis)
        assertNull(admitted.metrics.decline)
        assertEquals(1, exports)
    }

    @Test
    fun `cancelled and exhausted structural target attempts should not survive a fresh invocation`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) =
                Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED))
        }
        val cases = listOf(
            Triple(ExactContinuationLimits(maxRows = 0, maxWork = 0L), Cancellation.Never, ContinuationDecline.WORK),
            Triple(ExactContinuationLimits(maxRows = 0, maxTimeNs = 0L), Cancellation.Never, ContinuationDecline.TIME),
            Triple(ExactContinuationLimits(maxRows = 0), Cancellation { true }, ContinuationDecline.CANCELLED),
        )
        for ((limit, cancellation, reason) in cases) {
            val stopped = captureContinuationTarget(model, solver, null, limit, cancellation)
            val target = captureContinuationTarget(model, solver, null, ExactContinuationLimits(), Cancellation.Never)
            val retried = continueExactLp(model, target.basis, targetMetrics = target.metrics)

            assertEquals(reason, stopped.metrics.decline)
            assertNull(stopped.basis)
            assertNotNull(retried.witness)
            assertNull(retried.metrics.decline)
        }
    }

    @Test
    fun `repeated continuation should not replenish exhausted feasibility pivots`() {
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
    fun `source verification should resume a limited import`() {
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
    fun `mutable source edits should invalidate a retained candidate`() {
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
    fun `strict satisfaction continuation should verify an interior source point`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        for (upper in listOf(false, true)) {
            val source = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(
                    ExactLpColumn(
                        ExactLpBounds(
                            ExactLpSide(zero, strict = !upper),
                            ExactLpSide(one, strict = upper),
                        ),
                    ),
                ),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            )
            val model = assertNotNull(LpExactState(source).toWorkingModel())

            val result = continueExactLp(
                model,
                Basis(intArrayOf(), arrayOf(if (upper) VarStatus.AT_UPPER else VarStatus.AT_LOWER)),
            )

            val point = assertNotNull(result.witness).primal.single()
            assertTrue(point > BigFraction.ZERO && point < BigFraction.ONE)
            assertTrue(result.metrics.success)
            assertNull(result.conflict)
        }
    }

    @Test
    fun `nonzero objectives should retain strict closure verification`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )

        val result = continueExactLp(
            assertNotNull(LpExactState(source).toWorkingModel()),
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
        )

        assertNull(result.witness)
        assertNull(result.conflict)
        assertEquals(ContinuationDecline.CANDIDATE, result.metrics.decline)
    }

    @Test
    fun `a strict conflict should verify its scoped bound witnesses`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(9)))),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(source)
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 11L))
        assertTrue(trail.assertBound(1, false, ExactLpSide(zero), 12L))
        val state = trail.state

        val result = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
        )

        assertNull(result.witness)
        assertNotNull(result.conflict)
        val support = assertNotNull(result.support)
        assertTrue(support.state === state)
        assertEquals(setOf(11L, 12L), support.sides.map { it.witness }.toSet())
        assertEquals(listOf(9), assertNotNull(support.rows.single().second.premises).literalEntries())
    }

    @Test
    fun `retracting a strict side should restore the closed endpoint`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        val cache = LpExactContinuationCache()
        trail.push()
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 11L))
        val strict = continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache)
        trail.pop(0)

        val closed = continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache)

        assertTrue(assertNotNull(strict.witness).primal.single() > BigFraction.ZERO)
        assertEquals(BigFraction.ZERO, assertNotNull(closed.witness).primal.single())
        assertTrue(closed.metrics.invalidated)
    }

    @Test
    fun `strict interior witnesses should retain original coordinate origins`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(
                    ExactLpBounds(ExactLpSide(zero, strict = true), ExactLpSide(ExactLpNumber.of(1L))),
                    origin = ExactLpNumber.of(10L),
                ),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )

        val result = continueExactLp(
            assertNotNull(LpExactState(source).toWorkingModel()),
            Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER)),
        )

        val point = assertNotNull(result.witness).primal.single()
        assertTrue(point > BigFraction.ofLong(10) && point < BigFraction.ofLong(11))
    }

    @Test
    fun `a missing basis and a cancelled source import should decline separately`() {
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
    fun `either witness policy should be able to withhold continuation acceptance`() {
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
