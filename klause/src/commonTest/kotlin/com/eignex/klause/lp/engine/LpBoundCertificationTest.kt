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
            override fun observePhase(metrics: LpPhaseMetrics) { phase = metrics }
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
