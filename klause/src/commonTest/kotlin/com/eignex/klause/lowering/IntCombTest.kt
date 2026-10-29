package com.eignex.klause.lowering

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import kotlin.test.Test
import kotlin.test.assertTrue

class IntCombTest {
    @Test
    fun `cancelled variable terms should produce a constant combination`() {
        val terms = listOf(
            IntComb.Narrow(LinComb(mapOf(0 to 1L), 0L)),
            IntComb.Wide(WideLinComb(mapOf(0 to BIG_ONE), BIG_ZERO)),
        )

        for (term in terms) {
            val difference = sumIntCombs(listOf(term, term), negateTail = true)

            assertTrue(difference.isConstant())
        }
    }
}
