package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisSolveQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefactorPolicyTest {
    @Test
    fun `hard cap triggers exactly after sixty four accepted updates`() {
        val policy = RefactorPolicy()
        policy.recordFactorization(factorNnz = 100, buildWork = 10_000L, pivotSpread = 0.5)

        repeat(63) { update ->
            policy.recordBasisSolve(25L)
            policy.recordBasisSolve(25L)
            policy.recordAcceptedUpdate(10L)
            assertNull(policy.chooseAtSafePoint(update + 1, factorNnz = 100))
        }
        policy.recordAcceptedUpdate(10L)

        assertEquals(EngineRefactorTrigger.HARD_UPDATE_CAP, policy.chooseAtSafePoint(64, factorNnz = 100))
        assertEquals(64L, policy.metrics.acceptedUpdates)
    }

    @Test
    fun `fresh fill and repeated bound solves do not trigger adaptive rebuilds`() {
        val policy = RefactorPolicy()
        policy.recordFactorization(factorNnz = 10_000, buildWork = 100L, pivotSpread = 1e-12)

        repeat(32) {
            policy.recordBasisSolve(100L, boundUpdateFtran = true)
            assertNull(policy.chooseAtSafePoint(0, factorNnz = 10_000))
        }

        assertEquals(3_200L, policy.metrics.boundUpdateFtranWork)
        assertEquals(1e-12, policy.metrics.pivotSpread)
        assertTrue(policy.metrics.triggers.isEmpty())
    }

    @Test
    fun `sampled residual triggers once until the basis generation advances`() {
        val policy = RefactorPolicy()
        policy.recordFactorization(factorNnz = 20, buildWork = 1_000L, pivotSpread = 0.5)
        repeat(8) { policy.recordBasisSolve(10L) }
        assertTrue(policy.shouldSample(cancelled = false))
        assertFalse(policy.shouldSample(cancelled = true))
        val bad = BasisSolveQuality(1e-4, 1e-4)

        assertEquals(EngineRefactorTrigger.RESIDUAL, policy.chooseAtSafePoint(0, 20, quality = bad))
        assertNull(policy.chooseAtSafePoint(0, 20, quality = bad))
        policy.recordAcceptedUpdate(10L)
        assertEquals(EngineRefactorTrigger.RESIDUAL, policy.chooseAtSafePoint(1, 20, quality = bad))

        assertEquals(3L, policy.metrics.qualitySamples)
        assertEquals(2L, policy.metrics.residualTriggers)
        assertEquals(1L, policy.metrics.cooldownDeclines)
    }

    @Test
    fun `adaptive triggers require eight updates and use fresh factor fill`() {
        val policy = RefactorPolicy()
        policy.recordFactorization(factorNnz = 100, buildWork = 10_000L, pivotSpread = 0.25)
        repeat(7) { policy.recordAcceptedUpdate(1L) }

        assertNull(policy.chooseAtSafePoint(7, factorNnz = 1_000))
        policy.recordAcceptedUpdate(1L)

        assertEquals(EngineRefactorTrigger.FILL_GROWTH, policy.chooseAtSafePoint(8, factorNnz = 501))
    }

    @Test
    fun `unknown and saturated work cannot create a synthetic trigger`() {
        val unknown = RefactorPolicy()
        unknown.recordFactorization(factorNnz = 10, buildWork = null, pivotSpread = Double.NaN)
        repeat(8) {
            unknown.recordBasisSolve(null, boundUpdateFtran = true)
            unknown.recordAcceptedUpdate(null)
        }

        assertNull(unknown.chooseAtSafePoint(8, 10))
        assertEquals(16L, unknown.metrics.unknownWorkEvents)
        assertNull(unknown.metrics.pivotSpread)

        val saturated = RefactorPolicy()
        saturated.recordFactorization(factorNnz = 10, buildWork = 100L, pivotSpread = 1.0)
        repeat(8) { saturated.recordAcceptedUpdate(0L) }
        saturated.recordBasisSolve(Long.MAX_VALUE)

        assertNull(saturated.chooseAtSafePoint(8, 10))
        assertEquals(1L, saturated.metrics.saturatedWorkEvents)
    }

    @Test
    fun `backend advice precedes hard cap and numerical policy triggers`() {
        val policy = RefactorPolicy()
        policy.recordFactorization(factorNnz = 1, buildWork = 1L, pivotSpread = 1.0)
        repeat(64) { policy.recordAcceptedUpdate(1L) }

        assertEquals(
            EngineRefactorTrigger.BACKEND_SINGULAR,
            policy.chooseAtSafePoint(64, 10, backendRequested = true, backendSingular = true),
        )
    }
}
