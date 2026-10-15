package com.eignex.klause.ir

import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceIntDomainsTest {
    private fun openUpperBounds(): IntBounds = IntBounds.fromModelBounds(
        lowerBounds = longArrayOf(0),
        upperBounds = longArrayOf(0),
        openLo = null,
        openHi = Bits(1).also { it.set(0) },
    )

    @Test
    fun `a bounds declaration should admit its whole range`() {
        val declared = SourceIntDomains.ofBounds(
            IntBounds.fromModelBounds(longArrayOf(2), longArrayOf(9), null, null),
        )

        assertNull(declared.declaredOrNull(0))
        assertEquals(IntDomain(2, 9), declared.finiteDomain(0))
    }

    @Test
    fun `a non-contiguous declaration should keep the values its bounds cannot state`() {
        val holey = IntDomain(0, 6).excludeValue(2).excludeValue(4)
        val declared = SourceIntDomains.ofDomains(arrayOf(holey))

        assertEquals(holey, declared.declaredOrNull(0))
        assertEquals(2L, declared.declaredOrNull(0)!!.holeCount)
        assertEquals(0L, declared.bounds.lower(0))
        assertEquals(6L, declared.bounds.upper(0))
    }

    @Test
    fun `an open side should have no finite declaration to materialize`() {
        val declared = SourceIntDomains.ofBounds(openUpperBounds())

        assertFalse(declared.bounds.hasUpper(0))
        assertFailsWith<IllegalArgumentException> { declared.finiteDomain(0) }
    }

    @Test
    fun `an open-marked column should declare nothing though it carries a finite box`() {
        val declared = SourceIntDomains.ofDomains(
            arrayOf(IntDomain(0, 1L shl 40)),
            openHi = booleanArrayOf(true),
        )

        assertNull(declared.declaredOrNull(0))
        assertEquals(IntDomain(0, 1L shl 40), declared.finiteDomain(0))
        assertFalse(declared.bounds.hasUpper(0))
        assertTrue(declared.bounds.hasLower(0))
    }

    @Test
    fun `a shared declaration should read the array it was given rather than a copy`() {
        val domains = arrayOf(IntDomain(0, 1))
        val declared = SourceIntDomains.ofDomains(domains, shared = true)

        domains[0] = IntDomain(1, 1)

        assertEquals(IntDomain(1, 1), declared.finiteDomain(0))
    }

    @Test
    fun `rebounding an open column should replace its box rather than intersect it`() {
        val declared = SourceIntDomains.ofDomains(
            arrayOf(IntDomain(0, 6)),
            openHi = booleanArrayOf(true),
        )

        val tighter = declared.rebounded(IntBounds.fromModelBounds(longArrayOf(10), longArrayOf(10), null, null))

        assertEquals(IntDomain(10, 10), tighter?.finiteDomain(0))
    }

    @Test
    fun `rebounding should intersect a declared value set instead of replacing it`() {
        val declared = SourceIntDomains.ofDomains(arrayOf(IntDomain(0, 9).excludeValue(5)))

        val tighter = declared.rebounded(IntBounds.fromModelBounds(longArrayOf(3), longArrayOf(7), null, null))

        assertEquals(IntDomain(3, 7).excludeValue(5), tighter?.declaredOrNull(0))
    }

    @Test
    fun `rebounding a declared value set out of range should report no declaration`() {
        val declared = SourceIntDomains.ofDomains(arrayOf(IntDomain(0, 9).excludeValues(longArrayOf(4, 5, 6))!!))

        assertNull(declared.rebounded(IntBounds.fromModelBounds(longArrayOf(4), longArrayOf(6), null, null)))
    }

    @Test
    fun `a model declaring bounds alone should state no value set but materialize its range`() {
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(3), null, null),
            factors = emptyArray(),
        )

        assertNull(problem.intDomainOrNull(0))
        assertEquals(IntDomain(0, 3), problem.declaredIntDomains.finiteDomain(0))
    }
}
