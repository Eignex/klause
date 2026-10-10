package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.localsearch.invariantProjection
import kotlin.test.Test
import kotlin.test.assertSame

class GaussianXorPropagatorTest {

    @Test
    fun `GaussianXor has no local-search invariant`() {
        val factor = GaussianXor(listOf(Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), 0)))
        assertSame(NoInvariant, factor.invariantProjection(), "a propagator-only factor has no local-search role")
    }
}
