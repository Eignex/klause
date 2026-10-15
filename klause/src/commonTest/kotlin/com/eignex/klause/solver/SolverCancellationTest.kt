package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolverCancellationTest {
    @Test
    fun `withCancellation should not replace a lifetime cancellation`() {
        for (params in listOf<SolverParams>(BacktrackParams(), LocalSearchParams())) {
            val bound = params.withLifetimeCancellation(Cancellation { true })
                .withCancellation(Cancellation.Never)
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
        val token = BacktrackParams(cancellation = Cancellation { true }).cancellation

        assertTrue(token())
        assertFalse(token.lifetimeCancellation()())
    }
}
