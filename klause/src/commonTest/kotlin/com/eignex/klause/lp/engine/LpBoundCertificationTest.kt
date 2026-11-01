package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpBoundCertificationTest {
    @Test
    fun `large basis repair should retain admissible one sided residuals`() {
        val size = 129
        val zero = ExactLpNumber.of(0L)
        for (upper in listOf(false, true)) {
            val sign = if (upper) 1L else -1L
            val bounds = if (upper) ExactLpBounds(upper = ExactLpSide(zero)) else
                ExactLpBounds(ExactLpSide(zero))
            val source = assertNotNull(LpExactState(ExactLpModel(
                List(size) { column ->
                    listOf(column to 2L, (column + 1) % size to 1L).sortedBy { it.first }
                        .map { (row, coefficient) -> ExactLpEntry(row, ExactLpNumber.of(coefficient)) }
                },
                List(size) { zero },
                List(size * 2) { ExactLpColumn(bounds) },
                List(size) { ExactLpRow() },
                ExactLpObjective(List(size) { ExactLpNumber.of(sign * if (it == 0) 3L else 2L) } +
                    List(size) { zero }),
            )).toWorkingModel())
            val basis = Basis(IntArray(size) { it }, Array(size * 2) {
                if (it < size) VarStatus.BASIC else if (upper) VarStatus.AT_UPPER else VarStatus.AT_LOWER
            })

            val result = certifyLpBoundOnly(source, DoubleArray(size) { sign * 0.99 },
                ReconstructionLimits(maxAttempts = 1), basis = basis)

            assertEquals(BigFraction.ZERO, assertNotNull(result.bound).value)
        }
    }

    @Test
    fun `partial basis repair should still check untouched open directions`() {
        val size = 129
        val zero = ExactLpNumber.of(0L)
        val eighth = BigFraction.ofLong(8L).reciprocal()
        val source = assertNotNull(LpExactState(ExactLpModel(
            List(size) { column ->
                listOf(column to if (column == 0) 2L else -2L,
                    (column + 1) % size to if (column == size - 1) -1L else 1L)
                    .sortedBy { it.first }
                    .map { (row, coefficient) -> ExactLpEntry(row, ExactLpNumber.of(coefficient)) }
            },
            List(size) { zero },
            List(size * 2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(size) { ExactLpRow() },
            ExactLpObjective(List(size) { column ->
                when (column) {
                    0 -> ExactLpNumber.of(-1L)
                    size - 1 -> ExactLpNumber.of(eighth)
                    else -> zero
                }
            } + List(size) { zero }),
        )).toWorkingModel())
        val basis = Basis(IntArray(size) { it },
            Array(size * 2) { if (it < size) VarStatus.BASIC else VarStatus.AT_LOWER })

        val result = certifyLpBoundOnly(source, DoubleArray(size) { if (it == 0) -0.125 else 0.0 },
            ReconstructionLimits(maxAttempts = 1), basis = basis)

        assertNull(result.bound)
    }

    @Test
    fun `large basis repair should certify only admitted open directions`() {
        val size = 129
        val zero = ExactLpNumber.of(0L)
        val basis = Basis(IntArray(size) { it },
            Array(size * 2) { if (it < size) VarStatus.BASIC else VarStatus.AT_LOWER })
        for (boxed in listOf(true, false)) {
            val source = assertNotNull(LpExactState(ExactLpModel(
                List(size) { column ->
                    listOf(column to 2L, (column + 1) % size to 1L).sortedBy { it.first }
                        .map { (row, coefficient) -> ExactLpEntry(row, ExactLpNumber.of(coefficient)) }
                },
                List(size) { zero },
                List(size) { column -> ExactLpColumn(ExactLpBounds(ExactLpSide(zero),
                    ExactLpSide(ExactLpNumber.of(1L)).takeIf { boxed && column > 0 })) } +
                    List(size) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
                List(size) { ExactLpRow() },
                ExactLpObjective(List(size) { ExactLpNumber.of(-3L) } + List(size) { zero }),
            )).toWorkingModel())

            val result = certifyLpBoundOnly(source, DoubleArray(size) { -0.99 },
                ReconstructionLimits(maxAttempts = 1), basis = basis)

            assertEquals(0, result.metrics.pointChecks)
            if (boxed) {
                assertTrue(assertNotNull(result.bound).value < BigFraction.ZERO)
                assertEquals(1, assertNotNull(result.metrics.basisRepair).restrictedColumns)
            } else {
                assertNull(result.bound)
                assertEquals("DIMENSION", assertNotNull(result.metrics.basisRepair).decline)
            }
        }
    }

    @Test
    fun `open direction repair should avoid a structurally matched singular subsystem`() {
        val size = 129
        val zero = ExactLpNumber.of(0L)
        val source = assertNotNull(LpExactState(ExactLpModel(
            List(size) { column ->
                val entries = when (column) {
                    0 -> listOf(0 to 1L, 1 to 1L, 2 to 1L)
                    1 -> listOf(0 to 1L, 1 to 1L, 2 to 2L)
                    2 -> listOf(0 to 1L, 1 to 2L, 2 to 4L, 3 to 1L)
                    else -> listOf(column to 2L, (column + 1) % size to 1L)
                }
                entries.sortedBy { it.first }.map { (row, coefficient) ->
                    ExactLpEntry(row, ExactLpNumber.of(coefficient))
                }
            },
            List(size) { zero },
            List(size) { column -> ExactLpColumn(ExactLpBounds(ExactLpSide(zero),
                ExactLpSide(ExactLpNumber.of(1L)).takeIf { column >= 2 })) } +
                List(size) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(size) { ExactLpRow() },
            ExactLpObjective(List(size) { ExactLpNumber.of(if (it == 1) -4L else -3L) } +
                List(size) { zero }),
        )).toWorkingModel())
        val basis = Basis(IntArray(size) { it },
            Array(size * 2) { if (it < size) VarStatus.BASIC else VarStatus.AT_LOWER })

        val result = certifyLpBoundOnly(source, DoubleArray(size) { -0.99 },
            ReconstructionLimits(maxAttempts = 1), basis = basis)

        assertTrue(assertNotNull(result.bound).value < BigFraction.ZERO)
        assertEquals(2, assertNotNull(result.metrics.basisRepair).restrictedColumns)
        assertEquals(0, result.metrics.pointChecks)
    }

    @Test
    fun `retained basis should recover coupled dual equations without a primal`() {
        val zero = ExactLpNumber.of(0L)
        val source = assertNotNull(LpExactState(ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.of(3L)), ExactLpEntry(1, ExactLpNumber.of(1L))),
                listOf(ExactLpEntry(0, ExactLpNumber.of(2L)), ExactLpEntry(1, ExactLpNumber.of(4L))),
            ),
            listOf(ExactLpNumber.of(5L), ExactLpNumber.of(5L)),
            List(2) { ExactLpColumn(ExactLpBounds()) } +
                List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(2) { ExactLpRow() },
            ExactLpObjective(listOf(ExactLpNumber.of(-1L), ExactLpNumber.of(-1L), zero, zero)),
        )).toWorkingModel())
        val basis = Basis(intArrayOf(0, 1), arrayOf(VarStatus.BASIC, VarStatus.BASIC,
            VarStatus.AT_LOWER, VarStatus.AT_LOWER))

        val result = certifyLpBoundOnly(source, doubleArrayOf(-0.3 + 1e-10, -0.1 - 1e-10),
            ReconstructionLimits(maxAttempts = 1), basis = basis)

        assertEquals(BigFraction.ofLong(-2L), assertNotNull(result.bound).value)
        val repair = assertNotNull(result.metrics.basisRepair)
        assertEquals(2, repair.largestComponent)
        assertEquals(1, repair.solvedComponents)
        assertEquals(0, result.metrics.pointChecks)
        assertEquals(2, result.metrics.dualChecks)
        assertTrue(repair.factorWork > 0L)
    }

    @Test
    fun `singleton basis equations should recover fractional multipliers`() {
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        val result = certifyLpBoundOnly(model(upper = null, coefficient = 3L, rhs = 1L),
            doubleArrayOf(-1.0 / 3.0), ReconstructionLimits(maxAttempts = 1), basis = basis)

        assertEquals(BigFraction.ofLong(-3L).reciprocal(), assertNotNull(result.bound).value)
        assertEquals(1, assertNotNull(result.metrics.basisRepair).peeledRows)
        assertEquals(0, result.metrics.pointChecks)
    }

    @Test
    fun `deferred singleton columns should recover after the coupled dual core`() {
        val zero = ExactLpNumber.of(0L)
        val source = assertNotNull(LpExactState(ExactLpModel(
            listOf(
                listOf(ExactLpEntry(0, ExactLpNumber.of(3L)), ExactLpEntry(1, ExactLpNumber.of(1L))),
                listOf(ExactLpEntry(1, ExactLpNumber.of(2L)), ExactLpEntry(2, ExactLpNumber.of(1L))),
                listOf(ExactLpEntry(1, ExactLpNumber.of(1L)), ExactLpEntry(2, ExactLpNumber.of(3L))),
            ),
            listOf(ExactLpNumber.of(3L), ExactLpNumber.of(4L), ExactLpNumber.of(4L)),
            List(3) { ExactLpColumn(ExactLpBounds()) } +
                List(3) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(3) { ExactLpRow() },
            ExactLpObjective(List(3) { ExactLpNumber.of(-1L) } + List(3) { zero }),
        )).toWorkingModel())
        val basis = Basis(intArrayOf(0, 1, 2), Array(6) { if (it < 3) VarStatus.BASIC else VarStatus.AT_LOWER })

        val result = certifyLpBoundOnly(source, doubleArrayOf(-0.2 + 1e-10, -0.4 - 1e-10, -0.2 + 1e-10),
            ReconstructionLimits(maxAttempts = 1), basis = basis)

        assertEquals(BigFraction.ofLong(-3L), assertNotNull(result.bound).value)
        val repair = assertNotNull(result.metrics.basisRepair)
        assertEquals(1, repair.peeledColumns)
        assertEquals(1, repair.solvedComponents)
        assertEquals(2, result.metrics.dualChecks)
    }

    @Test
    fun `eliminated logical basics should contribute their costs to dual repair`() {
        val zero = ExactLpNumber.of(0L)
        val source = assertNotNull(LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)), ExactLpEntry(1, ExactLpNumber.of(3L)))),
            listOf(ExactLpNumber.of(5L), ExactLpNumber.of(3L)),
            listOf(ExactLpColumn(ExactLpBounds())) + List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            List(2) { ExactLpRow() },
            ExactLpObjective(listOf(ExactLpNumber.of(-1L), ExactLpNumber.of(2L), zero)),
        )).toWorkingModel())
        val basis = Basis(intArrayOf(1, 0), arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_LOWER))

        val result = certifyLpBoundOnly(source, doubleArrayOf(2.0, -1.0 + 1e-10),
            ReconstructionLimits(maxAttempts = 1), basis = basis)

        val bound = assertNotNull(result.bound)
        assertEquals(BigFraction.ofLong(7L), bound.value)
        assertEquals(listOf(BigFraction.ofLong(2L), BigFraction.MINUS_ONE), bound.dualMultipliers)
        assertEquals(2, result.metrics.dualChecks)
    }

    @Test
    fun `singular retained basis should supply no bound on an open objective`() {
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))

        val result = certifyLpBoundOnly(model(upper = null, coefficient = 0L), doubleArrayOf(-1.0),
            ReconstructionLimits(maxAttempts = 1), basis = basis)

        assertNull(result.bound)
        assertEquals("SINGULAR", assertNotNull(result.metrics.basisRepair).decline)
    }

    @Test
    fun `basis derived bounds should require both acceptance gates`() {
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        for (denied in listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_BASIS)) {
            val result = certifyLpBoundOnly(model(upper = null, coefficient = 3L, rhs = 1L),
                doubleArrayOf(-1.0 / 3.0), ReconstructionLimits(maxAttempts = 1),
                policy = LpCertificationPolicy { certifier, successful -> successful && certifier != denied },
                basis = basis)

            assertNull(result.bound)
            assertEquals(1, result.metrics.dualSuccesses)
            assertTrue(result.metrics.basisWork > 0L)
        }
    }

    @Test
    fun `basis repair observers should charge certificate work once`() {
        val basis = Basis(intArrayOf(0), arrayOf(VarStatus.BASIC, VarStatus.AT_LOWER))
        val certifiers = ArrayList<LpCertifier>()
        var work = 0L
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                certifiers += certifier
                assertTrue(success)
                work += (cost as LpCertifierCost.Metered).work
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
        }

        val result = certifyLpBoundOnly(model(upper = null, coefficient = 3L, rhs = 1L),
            doubleArrayOf(-1.0 / 3.0), ReconstructionLimits(maxAttempts = 1), observer = observer, basis = basis)

        assertNotNull(result.bound)
        assertEquals(listOf(LpCertifier.RATIONAL, LpCertifier.EXACT_BASIS), certifiers)
        assertEquals(result.metrics.work, work)
        assertTrue(result.metrics.basisWork > 0L)
    }

    @Test
    fun `independent dual denominators should certify a bound on free columns`() {
        val coefficients = listOf(101L, 103L, 107L, 109L, 1L)
        val zero = ExactLpNumber.of(0L)
        val source = assertNotNull(LpExactState(ExactLpModel(
            coefficients.mapIndexed { row, value -> listOf(ExactLpEntry(row, ExactLpNumber.of(value))) },
            coefficients.map { ExactLpNumber.of(1L) },
            coefficients.map { ExactLpColumn(ExactLpBounds()) } +
                coefficients.map { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            coefficients.map { ExactLpRow() },
            ExactLpObjective(coefficients.indices.map { ExactLpNumber.of(if (it < 4) -1L else 0L) } +
                coefficients.map { zero }),
        )).toWorkingModel())

        for (noise in listOf(0.0, -1e-8)) {
            val duals = coefficients.take(4).map { -1.0 / it } + noise
            val result = certifyLpBoundOnly(source, duals.toDoubleArray(), ReconstructionLimits(maxAttempts = 1))

            val multipliers = coefficients.take(4).map { BigFraction.ofLong(-it).reciprocal() } + BigFraction.ZERO
            val bound = assertNotNull(result.bound)
            assertEquals(multipliers, bound.dualMultipliers)
            assertEquals(multipliers.reduce(BigFraction::plus), bound.value)
            assertEquals(0, result.metrics.pointChecks)
            assertEquals(1, result.metrics.attempts)
            assertEquals(2, result.metrics.dualChecks)
            assertNotNull(result.metrics.vectorRejection)
            assertNotNull(bound.support)
        }
    }

    @Test
    fun `missing bound side should retain the exact reduced cost obstruction`() {
        val result = certifyLpBoundOnly(model(upper = null, coefficient = 3L, rhs = 1L),
            doubleArrayOf(-1.0 / 3.0), ReconstructionLimits(maxAttempts = 0))

        val rejection = assertNotNull(result.metrics.dualRejection)
        assertEquals(0, rejection.column)
        assertTrue(rejection.upper)
        assertEquals(BigFraction.ofLong(-1L) - exactDouble(-1.0 / 3.0) * BigFraction.ofLong(3L),
            rejection.reduced)
        assertNull(result.bound)
    }

    @Test
    fun `dual only certification should reconstruct fractional multipliers without recovering a primal`() {
        val result = certifyLpBoundOnly(model(upper = null, coefficient = 3L, rhs = 1L),
            doubleArrayOf(-1.0 / 3.0), ReconstructionLimits(maxAttempts = 1))
        val bound = assertNotNull(result.bound)

        assertEquals(BigFraction.ofLong(-3L).reciprocal(), bound.value)
        assertEquals(listOf(BigFraction.ofLong(-3L).reciprocal()), bound.dualMultipliers)
        assertNull(result.witness)
        assertNull(result.conflict)
        assertEquals(0, result.metrics.pointChecks)
        assertEquals(1, result.metrics.attempts)
        assertEquals(2, result.metrics.dualChecks)
    }

    @Test
    fun `zero reconstruction attempts should retain only the binary dual candidate`() {
        val result = certifyLpBoundOnly(model(upper = null, coefficient = 3L, rhs = 1L),
            doubleArrayOf(-1.0 / 3.0), ReconstructionLimits(maxAttempts = 0))

        assertNull(result.bound)
        assertEquals(ReconstructionDecline.CANDIDATE, result.metrics.decline)
        assertEquals(0, result.metrics.attempts)
        assertEquals(1, result.metrics.dualChecks)
        assertEquals(0, result.metrics.pointChecks)
    }

    @Test
    fun `dual reconstruction should share the direct candidate work and allocation allowances`() {
        val source = model(upper = null, coefficient = 3L, rhs = 1L)
        val duals = doubleArrayOf(-1.0 / 3.0)
        val direct = certifyLpBoundOnly(source, duals, ReconstructionLimits(maxAttempts = 0))
        val allowances = listOf(
            ReconstructionLimits(maxAttempts = 1, maxWork = direct.metrics.work) to ReconstructionDecline.WORK,
            ReconstructionLimits(maxAttempts = 1, maxAllocation = direct.metrics.allocation) to
                ReconstructionDecline.ALLOCATION,
        )
        for ((limits, reason) in allowances) {
            val result = certifyLpBoundOnly(source, duals, limits)

            assertNull(result.bound)
            assertEquals(reason, result.metrics.decline)
            assertTrue(result.metrics.work <= limits.maxWork)
            assertTrue(result.metrics.allocation <= limits.maxAllocation)
            assertEquals(0, result.metrics.pointChecks)
        }
    }

    @Test
    fun `dual only certification should produce a bound without primal or basis work`() {
        val result = certifyLpBoundOnly(model(), doubleArrayOf(-1.0), ReconstructionLimits())

        assertEquals(BigFraction.ofLong(-5L), assertNotNull(result.bound).value)
        assertNull(result.witness)
        assertNull(result.conflict)
        assertEquals(0, result.metrics.pointChecks)
        assertEquals(1, result.metrics.dualChecks)
        assertEquals(1, result.metrics.dualSuccesses)
        assertEquals(0, result.metrics.attempts)
    }

    @Test
    fun `unsupported slack multiplier should weaken the bound instead of supplying authority`() {
        val result = certifyLpBoundOnly(model(), doubleArrayOf(1.0), ReconstructionLimits())
        val bound = assertNotNull(result.bound)

        assertEquals(BigFraction.ofLong(-10L), bound.value)
        assertEquals(listOf(BigFraction.ZERO), bound.dualMultipliers)
    }

    @Test
    fun `dual only certification should report the limiting resource independently of a primal`() {
        val cases = listOf(
            ReconstructionLimits(maxCoordinates = 1) to ReconstructionDecline.DIMENSION,
            ReconstructionLimits(maxWork = 0L) to ReconstructionDecline.WORK,
            ReconstructionLimits(maxAllocation = 0L) to ReconstructionDecline.ALLOCATION,
        )
        for ((limits, reason) in cases) {
            val result = certifyLpBoundOnly(model(), doubleArrayOf(-1.0), limits)

            assertNull(result.bound)
            assertEquals(reason, result.metrics.decline)
            assertEquals(0, result.metrics.pointChecks)
        }
    }

    @Test
    fun `cancelled and nonfinite dual candidates should supply no bound`() {
        val cancelled = certifyLpBoundOnly(model(), doubleArrayOf(-1.0), ReconstructionLimits(), Cancellation { true })
        val nonfinite = certifyLpBoundOnly(model(), doubleArrayOf(Double.NaN), ReconstructionLimits())

        assertNull(cancelled.bound)
        assertEquals(ReconstructionDecline.CANCELLED, cancelled.metrics.decline)
        assertNull(nonfinite.bound)
        assertEquals(ReconstructionDecline.NONFINITE, nonfinite.metrics.decline)
    }

    @Test
    fun `dual only certification should honor proof policy and report its cost`() {
        val certifiers = ArrayList<LpCertifier>()
        var work = 0L
        var phase: LpPhaseMetrics? = null
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) {
                certifiers += certifier
                assertTrue(success)
                work += (cost as LpCertifierCost.Metered).work
            }
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observePhase(metrics: LpPhaseMetrics) {
                if (metrics.phase == LpSolvePhase.BOUND_CERTIFICATE) phase = metrics
            }
        }

        val result = certifyLpBoundOnly(model(), doubleArrayOf(-1.0), ReconstructionLimits(), observer = observer,
            policy = LpCertificationPolicy { _, _ -> false })

        assertNull(result.bound)
        assertEquals(listOf(LpCertifier.RATIONAL), certifiers)
        assertEquals(result.metrics.work, work)
        assertTrue(work > 0L)
        assertEquals(LpSolvePhase.BOUND_CERTIFICATE, phase?.phase)
        assertEquals("REFUSED", phase?.outcome)
    }

    private fun model(upper: Long? = 10L, coefficient: Long = 1L, rhs: Long = 5L): LpModel {
        val zero = ExactLpNumber.of(0L)
        return assertNotNull(LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(coefficient)))),
            listOf(ExactLpNumber.of(rhs)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), upper?.let { ExactLpSide(ExactLpNumber.of(it)) })),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
        )).toWorkingModel())
    }
}
