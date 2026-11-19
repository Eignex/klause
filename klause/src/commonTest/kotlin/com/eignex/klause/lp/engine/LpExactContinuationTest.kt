package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ContinuationDecline
import com.eignex.klause.simplex.exact.ContinuationPhase
import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpExactContinuationTest {
    @Test
    fun `target admission should spend caller funding beyond fixed work and allocation envelopes`() {
        val rows = 128
        val zero = ExactLpNumber.of(0L)
        val premises = ExactLpPremises(emptyList(), List(8192) { it })
        val source = ExactLpModel(
            listOf(emptyList()), List(rows) { zero },
            List(rows + 1) { ExactLpColumn(ExactLpBounds()) },
            List(rows) { ExactLpRow(premises = premises) },
            ExactLpObjective(List(rows + 1) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        var exports = 0
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel): Basis {
                exports++
                return Basis(IntArray(rows) { it + 1 },
                    Array(rows + 1) { if (it == 0) VarStatus.FREE else VarStatus.BASIC })
            }
        }
        val limits = ExactContinuationLimits(maxWork = 4_000_000L, maxAllocation = 16L * 1024L * 1024L)
        val cases = listOf(
            limits.copy(maxWork = 1_000_000L) to ContinuationDecline.WORK,
            limits.copy(maxAllocation = 4L * 1024L * 1024L) to ContinuationDecline.ALLOCATION,
        )
        for ((grant, reason) in cases) {
            val refused = captureContinuationTarget(model, solver, null, grant, ExecutionControl.Never)
            assertEquals(reason, refused.metrics.decline)
            assertNull(refused.basis)
        }
        assertEquals(0, exports)

        val target = captureContinuationTarget(model, solver, null, limits, ExecutionControl.Never)

        assertNotNull(target.basis)
        assertNull(target.metrics.decline)
        assertTrue(target.metrics.work > 1_000_000L)
        assertTrue(target.metrics.allocation > 4L * 1024L * 1024L)
        assertEquals(1, exports)
    }

    @Test
    fun `continuation admission and source capture should share the caller grant`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        val cases = listOf(
            ExactContinuationLimits(maxWork = 100L) to ContinuationDecline.WORK,
            ExactContinuationLimits(maxAllocation = 1500L) to ContinuationDecline.ALLOCATION,
        )
        for ((limits, reason) in cases) {
            val cache = LpExactContinuationCache()

            val result = continueExactLp(model, basis, cache, limits = limits)

            assertNull(result.witness)
            assertEquals(reason, result.metrics.decline)
            assertEquals(ContinuationPhase.INPUT, result.metrics.phase)
            assertTrue(result.metrics.work <= limits.maxWork)
            assertTrue(result.metrics.allocation <= limits.maxAllocation)
            assertEquals(result.metrics.work, cache.usedWork)
            assertEquals(result.metrics.allocation, cache.usedAllocation)
        }
    }

    @Test
    fun `funded identity admission should stop before unaffordable source capture`() {
        val rows = 128
        val zero = ExactLpNumber.of(0L)
        val premises = ExactLpPremises(emptyList(), List(8192) { it })
        val source = ExactLpModel(
            listOf(emptyList()), List(rows) { zero },
            List(rows + 1) { ExactLpColumn(ExactLpBounds()) },
            List(rows) { ExactLpRow(premises = premises) },
            ExactLpObjective(List(rows + 1) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(IntArray(rows) { it + 1 },
            Array(rows + 1) { if (it == 0) VarStatus.FREE else VarStatus.BASIC })
        val cache = LpExactContinuationCache()
        val limits = ExactContinuationLimits(maxWork = 4_000_000L, maxAllocation = 16L * 1024L * 1024L)

        val result = continueExactLp(model, basis, cache, limits = limits)

        assertEquals(ContinuationPhase.INPUT, result.metrics.phase)
        assertEquals(ContinuationDecline.ALLOCATION, result.metrics.decline)
        assertNull(result.witness)
        assertNull(result.conflict)
        assertEquals(0, result.metrics.builds)
        assertTrue(result.metrics.work > 1_000_000L && result.metrics.work <= limits.maxWork)
        assertTrue(result.metrics.allocation > 4L * 1024L * 1024L && result.metrics.allocation <= limits.maxAllocation)
        assertEquals(result.metrics.work, cache.usedWork)
        assertEquals(result.metrics.allocation, cache.usedAllocation)
    }

    @Test
    fun `failed admission should retain spending when the caller extends its grant`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 1L) }
            .build(Sense.MINIMIZE).authoritativeModel())
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        val cache = LpExactContinuationCache()
        val limited = continueExactLp(model, basis, cache, limits = ExactContinuationLimits(maxWork = 2L))

        val funded = continueExactLp(model, basis, cache, limits = ExactContinuationLimits(maxWork = 50L))

        assertEquals(ContinuationDecline.WORK, limited.metrics.decline)
        assertEquals(ContinuationPhase.INPUT, funded.metrics.phase)
        assertEquals(limited.metrics.work + funded.metrics.work, cache.usedWork)
        assertEquals(limited.metrics.allocation + funded.metrics.allocation, cache.usedAllocation)
    }

    @Test
    fun `caller funded admission beyond four mebibytes should reach a verified witness`() {
        val rows = 64
        val zero = ExactLpNumber.of(0L)
        val premises = ExactLpPremises(emptyList(), List(8192) { it })
        val source = ExactLpModel(
            listOf(emptyList()), List(rows) { zero },
            List(rows + 1) { ExactLpColumn(ExactLpBounds()) },
            List(rows) { ExactLpRow(premises = premises) },
            ExactLpObjective(List(rows + 1) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val cache = LpExactContinuationCache()
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) = Basis(IntArray(rows) { it + 1 },
                Array(rows + 1) { if (it == 0) VarStatus.FREE else VarStatus.BASIC })
        }

        val result = certifyLpResult(model, solver, null, continuationCache = cache,
            claim = LpClaim.FEASIBLE_COMPLETION)

        assertEquals(listOf(BigFraction.ZERO), assertNotNull(result.witness).primal)
        val metrics = assertNotNull(result.continuation)
        assertTrue(assertNotNull(metrics.allocationByPhase[ContinuationPhase.ADMISSION]) > 4L * 1024L * 1024L)
        assertEquals(metrics.work, cache.usedWork)
        assertEquals(metrics.allocation, cache.usedAllocation)
    }

    @Test
    fun `retained certification should fund target export from the remaining grant`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 1L) }
            .build(Sense.MINIMIZE).authoritativeModel())
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        var exports = 0
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel): Basis {
                exports++
                return Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
            }
        }
        val cache = LpExactContinuationCache()
        cache.accountInput(100L, 0L, 0L)

        val result = certifyLpResult(model, solver, null, continuationCache = cache,
            continuationLimits = ExactContinuationLimits(maxWork = 100L), claim = LpClaim.FEASIBLE_COMPLETION)

        assertEquals(0, exports)
        assertNull(result.witness)
        assertEquals(ContinuationDecline.WORK, assertNotNull(result.continuation).decline)
        assertEquals(100L, cache.usedWork)
    }

    @Test
    fun `cancelled target export should retain the copy cost without publishing the basis`() {
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val cache = LpExactContinuationCache()
        var cancelled = false
        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel): Basis {
                cancelled = true
                return Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
            }
        }

        val target = captureContinuationTarget(model, solver, null, ExactContinuationLimits(),
            ExecutionControl { cancelled }, cache)

        assertNull(target.basis)
        assertEquals(ContinuationDecline.CANCELLED, target.metrics.decline)
        assertTrue(cache.usedAllocation > 0L)
        assertEquals(target.metrics.work, cache.usedWork)
        assertEquals(target.metrics.allocation, cache.usedAllocation)
    }

    @Test
    fun `replacing an incompatible checkpoint should retain its costs`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 1L)
        }.build(Sense.MINIMIZE)
        val basis = Basis(intArrayOf(1), arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC))
        val cache = LpExactContinuationCache()
        cache.accountInput(0L, 0L, 1_000_000_000L)
        val first = continueExactLp(model, basis, cache)
        model.rhs[0] = -2L

        val changed = continueExactLp(model, basis, cache)

        assertTrue(changed.metrics.invalidated)
        assertEquals(first.metrics.work + changed.metrics.work, cache.usedWork)
        assertEquals(first.metrics.allocation + changed.metrics.allocation, cache.usedAllocation)
        assertTrue(cache.usedTimeNs >= 1_000_000_000L)
    }

    @Test
    fun `continuation verification should admit caller funded columns beyond the default envelope`() {
        val columns = 4097
        val zero = ExactLpNumber.of(0L)
        val column = ExactLpColumn(ExactLpBounds())
        val source = ExactLpModel(
            List(columns) { emptyList() }, emptyList(),
            List(columns) { column },
            emptyList(), ExactLpObjective(List(columns) { zero }),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(), Array(columns) { VarStatus.FREE })

        val declined = continueExactLp(model, basis)
        val result = continueExactLp(model, basis, limits = ExactContinuationLimits(maxColumns = columns))

        assertEquals(ContinuationDecline.DIMENSION, declined.metrics.decline)
        assertEquals(List(columns) { BigFraction.ZERO }, assertNotNull(result.witness).primal)
        assertNull(result.metrics.decline)
    }

    @Test
    fun `continuation verification should honor the caller scalar allowance`() {
        val tiny = BigFraction.of(BIG_ONE, BIG_ONE shl 4200)
        val value = ExactLpNumber.of(tiny)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(value), ExactLpSide(value)))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )
        val model = assertNotNull(LpExactState(source).toWorkingModel())
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.FIXED))

        val result = continueExactLp(model, basis, limits = ExactContinuationLimits(maxBits = 8192))

        assertEquals(listOf(tiny), assertNotNull(result.witness).primal)
        assertNull(result.metrics.decline)
    }

    @Test
    fun `cell bounded target admission should allow rows beyond a fixed row threshold`() {
        val rows = 149
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            repeat(rows) { addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L) }
        }.build(Sense.MINIMIZE)

        val solver = object : LpSolver {
            override val infeasibleRay: DoubleArray? = null
            override fun solve(warm: Basis?): FloatLpResult? = null
            override fun solvePrimal(warm: Basis?): FloatLpResult? = null
            override fun continuationBasis(model: LpModel) = Basis(
                IntArray(rows) { it + 1 },
                Array(rows + 1) { if (it == 0) VarStatus.AT_LOWER else VarStatus.BASIC },
            )
        }

        val result = captureContinuationTarget(model, solver, null, ExactContinuationLimits(), ExecutionControl.Never)

        assertNotNull(result.basis)
        assertNull(result.metrics.decline)
    }

    @Test
    fun `logical columns should certify scoped conflicts after basis import`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            List(2) { listOf(ExactLpEntry(0, one), ExactLpEntry(1, one)) },
            listOf(one, ExactLpNumber.of(2L)),
            List(2) { ExactLpColumn(ExactLpBounds()) } +
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))) },
            List(2) { ExactLpRow(global = false, premises = ExactLpPremises(emptyList(), listOf(9 + it))) },
            ExactLpObjective(List(4) { zero }),
        )
        val state = LpExactState(source)

        val result = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            Basis(intArrayOf(0, 3), arrayOf(VarStatus.BASIC, VarStatus.FREE, VarStatus.FIXED, VarStatus.BASIC)),
        )

        assertNotNull(result.conflict)
        assertNull(result.witness)
        val support = assertNotNull(result.support)
        assertTrue(support.state === state)
        assertEquals(setOf(9, 10), support.rows.flatMap { assertNotNull(it.second.premises).literalEntries() }.toSet())
    }

    @Test
    fun `cell admission should include only stored constraint columns and right hand side`() {
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            addRow(intArrayOf(0), longArrayOf(1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE)

        val result = continueExactLp(
            model,
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FIXED)),
            limits = ExactContinuationLimits(maxCells = 3),
        )

        assertEquals(listOf(BigFraction.ONE), assertNotNull(result.witness).primal)
        assertNull(result.metrics.decline)
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
            ExactContinuationLimits(maxCells = 2),
        )
        for (limit in limits) {
            repeat(2) {
                val declined = captureContinuationTarget(model, solver, null, limit, ExecutionControl.Never)
                assertEquals(ContinuationDecline.DIMENSION, declined.metrics.decline)
                assertEquals(1L, declined.metrics.work)
                assertEquals(0L, declined.metrics.allocation)
                assertNull(declined.basis)
            }
        }
        assertEquals(0, exports)

        val admitted = captureContinuationTarget(model, solver, null, ExactContinuationLimits(), ExecutionControl.Never)

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
            Triple(ExactContinuationLimits(maxRows = 0, maxWork = 0L), ExecutionControl.Never,
                ContinuationDecline.WORK),
            Triple(ExactContinuationLimits(maxRows = 0, maxTimeNs = 0L), ExecutionControl.Never,
                ContinuationDecline.TIME),
            Triple(ExactContinuationLimits(maxRows = 0), ExecutionControl { true }, ContinuationDecline.CANCELLED),
        )
        for ((limit, cancellation, reason) in cases) {
            val stopped = captureContinuationTarget(model, solver, null, limit, cancellation)
            val cache = LpExactContinuationCache()
            val target = captureContinuationTarget(model, solver, null, ExactContinuationLimits(),
                ExecutionControl.Never, cache)
            val retried = continueExactLp(model, target.basis, cache, targetMetrics = target.metrics)

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
    fun `unchanged partial imports should retain the exhausted import allowance`() {
        for (arbitrary in listOf(false, true)) {
            val model = LpBuilder().apply {
                repeat(2) { j ->
                    addVar(0L, 10L)
                    addRow(intArrayOf(j), longArrayOf(3L), Relation.EQ, 1L)
                }
            }.build(Sense.MINIMIZE)
            val basis = Basis(
                intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED),
            )
            val cache = LpExactContinuationCache()
            val limits = ExactContinuationLimits(maxImportPivots = 1, startArbitraryPrecision = arbitrary)
            val first = continueExactLp(model, basis, cache, limits = limits)
            val spent = cache.usedWork

            val repeated = continueExactLp(model, basis, cache, limits = limits)

            assertEquals(ContinuationDecline.IMPORT_LIMIT, first.metrics.decline)
            assertEquals(1, first.metrics.imports)
            assertEquals(ContinuationDecline.IMPORT_LIMIT, repeated.metrics.decline)
            assertEquals(0, repeated.metrics.imports)
            assertEquals(0, repeated.metrics.builds)
            assertEquals(first.metrics.importPosition, repeated.metrics.importPosition)
            assertTrue(repeated.metrics.resumed)
            assertFalse(repeated.metrics.invalidated)
            assertTrue(cache.usedWork > spent)
            assertNull(repeated.witness)
            assertNull(repeated.conflict)
        }
    }

    @Test
    fun `a changed ordered target should invalidate an unfinished import`() {
        for (arbitrary in listOf(false, true)) {
            val model = LpBuilder().apply {
                repeat(2) { j ->
                    addVar(0L, 10L)
                    addRow(intArrayOf(j), longArrayOf(3L), Relation.EQ, 1L)
                }
            }.build(Sense.MINIMIZE)
            val statuses = arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.FIXED, VarStatus.FIXED)
            val cache = LpExactContinuationCache()
            val limits = ExactContinuationLimits(maxImportPivots = 1, startArbitraryPrecision = arbitrary)
            assertEquals(ContinuationDecline.IMPORT_LIMIT,
                continueExactLp(model, Basis(intArrayOf(0, 1), statuses), cache, limits = limits).metrics.decline)

            val changed = continueExactLp(model, Basis(intArrayOf(1, 0), statuses), cache, limits = limits)

            assertEquals(ContinuationDecline.IMPORT_LIMIT, changed.metrics.decline)
            assertTrue(changed.metrics.invalidated)
            assertFalse(changed.metrics.resumed)
            assertEquals(1, changed.metrics.imports)
            assertEquals(1, changed.metrics.builds)
            assertNull(changed.witness)
            assertNull(changed.conflict)
        }
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
        val cache = LpExactContinuationCache()
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.FREE))
        assertNotNull(continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache).witness)
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, strict = true), 11L))
        assertTrue(trail.assertBound(1, false, ExactLpSide(zero), 12L))
        val state = trail.state

        val result = continueExactLp(
            assertNotNull(state.toWorkingModel()),
            Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER)),
            cache,
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
        assertFalse(closed.metrics.invalidated)
    }

    @Test
    fun `retracting conflicting bounds should restore a coupled source witness`() {
        val source = assertNotNull(LpBuilder().apply {
            addVar(0L, 10L)
            addVar(0L, 10L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.EQ, 1L)
        }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        val basis = Basis(intArrayOf(2), arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER, VarStatus.BASIC))
        val cache = LpExactContinuationCache()
        assertNotNull(continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache).witness)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 11L))
        val conflict = continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache)
        assertNotNull(conflict.conflict)
        assertTrue(trail.pop(0))

        val restored = continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache)

        val point = assertNotNull(restored.witness).primal
        assertEquals(BigFraction.ONE, point[0] + point[1])
        assertTrue(point.all { it >= BigFraction.ZERO })
        assertNull(restored.conflict)
    }

    @Test
    fun `bound edits should retain the exhausted continuation allowance`() {
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }
            .build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
        val cache = LpExactContinuationCache()
        assertNotNull(continueExactLp(assertNotNull(trail.state.toWorkingModel()), basis, cache).witness)
        val spent = cache.usedWork
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 11L))

        val limited = continueExactLp(
            assertNotNull(trail.state.toWorkingModel()), basis, cache,
            limits = ExactContinuationLimits(maxWork = spent),
        )

        assertNull(limited.witness)
        assertNull(limited.conflict)
        assertEquals(ContinuationDecline.WORK, limited.metrics.decline)
    }

    @Test
    fun `a cancelled bound update should allow a current source retry`() {
        for (arbitrary in listOf(false, true)) {
            val source = assertNotNull(LpBuilder().apply { addVar(0L, 10L) }
                .build(Sense.MINIMIZE).authoritativeModel())
            val trail = LpBoundTrail(source)
            val basis = Basis(intArrayOf(), arrayOf(VarStatus.AT_LOWER))
            val cache = LpExactContinuationCache()
            val limits = ExactContinuationLimits(startArbitraryPrecision = arbitrary)
            val initial = assertNotNull(trail.state.toWorkingModel())
            assertNotNull(continueExactLp(initial, basis, cache, limits = limits).witness)
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 11L))
            val model = assertNotNull(trail.state.toWorkingModel())
            val stopped = continueExactLp(model, basis, cache, limits = limits, cancellation =
                ExecutionControl { true })
            assertNull(stopped.witness)

            val retried = continueExactLp(model, basis, cache, limits = limits)

            assertEquals(listOf(BigFraction.ofLong(2)), assertNotNull(retried.witness).primal)
            assertNull(retried.conflict)
        }
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
            cancellation = ExecutionControl { true },
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
