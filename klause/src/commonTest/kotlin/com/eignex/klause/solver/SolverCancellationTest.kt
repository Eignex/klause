package com.eignex.klause.solver

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.pipeline.QuadraticNativeScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolverCancellationTest {
    @Test
    fun `a fresh invocation should preserve the lifetime arithmetic allowance`() {
        val scope = QuadraticNativeScope(PolynomialTheoryParams(maximumWork = 3), concurrent = false)
        val lifetime = ExecutionControl.Never.withLifetime(scope.polynomial)
        val first = lifetime.forInvocation(ExecutionControl.Never)
        PolynomialArithmetic(first).multiply(Polynomial.ONE, Polynomial.ONE)
        val paused = lifetime.forInvocation(ExecutionControl { true })

        assertFailsWith<PolynomialInterrupted> {
            PolynomialArithmetic(paused).multiply(Polynomial.ONE, Polynomial.ONE)
        }
        assertEquals(2L, scope.work)
        val resumed = lifetime.forInvocation(ExecutionControl.Never)
        assertFailsWith<PolynomialInterrupted> {
            PolynomialArithmetic(resumed).multiply(Polynomial.ONE, Polynomial.ONE)
        }

        assertEquals(3L, scope.work)
        assertTrue(scope.stopped)
    }

    @Test
    fun `work scheduling should survive dynamic scoped and composite cancellation`() {
        val diagnostic = object : ExecutionControl() {
            override val workScheduled = true
            override fun isCancelled() = false
        }
        val adapted = cancelledWhen(diagnostic::deadline, diagnostic::workScheduled) { diagnostic() }
        val params = BacktrackParams().withLifetimeCancellation(adapted)
            .withCancellation(ExecutionControl.Never or diagnostic)

        assertTrue(params.cancellation.workScheduled())
        assertTrue(params.cancellation.lifetimeCancellation().workScheduled())
        assertFalse(params.cancellation())
    }

    @Test
    fun `withCancellation should not replace a lifetime cancellation`() {
        for (params in listOf<SolverParams>(BacktrackParams(), LocalSearchParams())) {
            val bound = params.withLifetimeCancellation(ExecutionControl { true })
                .withCancellation(ExecutionControl.Never)
            val token = when (bound) {
                is BacktrackParams -> bound.cancellation
                is LocalSearchParams -> bound.cancellation
                else -> error("unsupported parameters")
            }

            assertTrue(token())
            assertTrue(token.lifetimeCancellation()())
        }
    }

    @Test
    fun `constructor cancellation should not be a lifetime cancellation`() {
        val token = BacktrackParams(cancellation = ExecutionControl { true }).cancellation

        assertTrue(token())
        assertFalse(token.lifetimeCancellation()())
    }
}
