package com.eignex.klause.arithmetic.difference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class DifferenceFragmentTest {
    @Test
    fun `unknown endpoints should return the missing node sentinel`() {
        val fragment = DifferenceFragment(listOf(DifferenceEdge(2, 10, 0L)))

        for (endpoint in listOf(0, 5, 11)) {
            assertEquals(-1, fragment.nodeOf(endpoint))
        }
    }

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
