package com.eignex.klause.solver.pipeline

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class QuadraticNativeScopeTest {
    @Test
    fun `mixed phase shortens the invocation deadline when its worker has none`() {
        val timeout = Cancellation.after(100.seconds)
        val native = QuadraticNativeScope(PolynomialTheoryParams(timeout = timeout))

        val phase = native.phaseCancellation(Cancellation.Never, 0.25)

        assertTrue(requireNotNull(phase.deadline()) < requireNotNull(timeout.deadline()))
    }

    @Test
    fun `worker cancellation survives the mixed deadline slice`() {
        val native = QuadraticNativeScope(PolynomialTheoryParams(timeout = Cancellation.after(100.seconds)))

        val phase = native.phaseCancellation(Cancellation { true }, 0.25)

        assertTrue(phase())
    }
}
