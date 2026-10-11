package com.eignex.klause.cli

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals

class PolynomialSolvableTest {
    @Test
    fun `polynomial strategy controls reach the finite workers`() {
        val common = CommonOptions().apply {
            randomSeed = 17
            engineParams.addAll(listOf("max-decisions=3", "max-flips=7", "max-instructions=11", "alns-iterations=2"))
        }

        val params = polynomialParams(common, Cancellation.Never).strategy

        assertEquals(17L, params.backtrack.randomSeed)
        assertEquals(17L, params.localSearch.randomSeed)
        assertEquals(3L, params.backtrack.maxDecisions)
        assertEquals(7L, params.localSearch.maxFlips)
        assertEquals(11L, params.localSearch.maxInstructions)
        assertEquals(2, params.alnsIterations)
    }
}
