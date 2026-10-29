package com.eignex.klause.arithmetic.difference

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class DifferenceFragmentTest {
    @Test
    fun `minimum long source edge cannot carry a potential`() {
        val fragment = DifferenceFragment(listOf(DifferenceEdge(DifferenceFragment.ZERO, 0, Long.MIN_VALUE)))

        assertFalse(fragment.carriesAPotential())
        assertNull(fragment.withoutOverHeavyDomainEdges())
    }

    @Test
    fun `minimum long domain edge is omitted from the redundant graph`() {
        val source = DifferenceEdge(0, 1, 0L)
        val domain = DifferenceEdge(DifferenceFragment.ZERO, 0, Long.MIN_VALUE, domainBound = true)
        val fragment = DifferenceFragment(listOf(source, domain))

        val retained = fragment.withoutOverHeavyDomainEdges()

        assertSame(source, retained?.edges?.single())
    }
}
