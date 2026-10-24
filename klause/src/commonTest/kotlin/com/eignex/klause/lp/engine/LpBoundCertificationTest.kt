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
    fun `dual only certification should produce a bound without primal or basis work`() {
        val result = certifyLpBoundOnly(model(), doubleArrayOf(-1.0), ReconstructionLimits())

        assertEquals(BigFraction.ofLong(-5L), assertNotNull(result.bound).value)
        assertNull(result.witness)
        assertNull(result.conflict)
        assertEquals(0, result.metrics.pointChecks)
        assertEquals(1, result.metrics.dualChecks)
        assertEquals(1, result.metrics.dualSuccesses)
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

    private fun model(): LpModel {
        val zero = ExactLpNumber.of(0L)
        return assertNotNull(LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(1L)))),
            listOf(ExactLpNumber.of(5L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)),
        )).toWorkingModel())
    }
}
