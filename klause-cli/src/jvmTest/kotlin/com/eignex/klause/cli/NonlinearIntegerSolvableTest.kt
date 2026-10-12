package com.eignex.klause.cli

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NonlinearIntegerSolvableTest {
    @Test
    fun `nonlinear source controls retain explicit candidate and move allowances`() {
        val common = CommonOptions().apply {
            engineParams.addAll(listOf("nia-candidates=7", "nia-moves=11", "nra-work-limit=128"))
        }

        val params = nonlinearIntegerParams(common, Cancellation.Never, null)

        assertEquals(7L, params.maximumCandidates)
        assertEquals(11L, params.maximumMoves)
        assertEquals(128L, params.polynomial.maximumWork)
    }

    @Test
    fun `polynomial finite workers share one generic node allowance`() {
        val common = CommonOptions().apply { engineParams.add("node-limit=7") }

        val params = polynomialParams(common, Cancellation.Never)

        assertEquals(7L, common.nodeBudget?.limit)
        assertSame(common.nodeBudget, params.strategy.backtrack.nodeBudget)
        assertSame(common.nodeBudget, params.strategy.localSearch.nodeBudget)
    }
}
