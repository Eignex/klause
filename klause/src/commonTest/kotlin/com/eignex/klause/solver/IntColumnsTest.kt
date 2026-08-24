package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * What is known about a column, stated by the column itself. A theory-owned column carries the bounds
 * it is reasoned over rather than standing for their absence and sending the reader elsewhere.
 */
class IntColumnsTest {

    @Test
    fun `a finite column reports its domain and its bounds`() {
        val columns = FiniteIntColumns(arrayOf(IntDomain(2, 9)))

        assertEquals(IntDomain(2, 9), columns.domainOrNull(0))
        assertEquals(IntColumn.Bounded(2, 9), columns.boundsOf(0))
    }

    @Test
    fun `a theory column has no domain but still answers its bounds`() {
        val columns = MixedIntColumns(arrayOf(IntColumn.Bounded(lower = 5, upper = null)))

        assertNull(columns.domainOrNull(0))
        assertEquals(IntColumn.Bounded(5, null), columns.boundsOf(0))
    }

    @Test
    fun `an open side is representable rather than merely absent`() {
        val columns = MixedIntColumns(arrayOf(IntColumn.Bounded(lower = null, upper = null)))

        assertEquals(IntColumn.Bounded(null, null), columns.boundsOf(0))
    }

    @Test
    fun `packed finite columns hand back their array without copying`() {
        val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 1))
        val columns = FiniteIntColumns(domains, shared = true)

        assertEquals(domains, columns.allFiniteOrNull())
    }

    @Test
    fun `a problem holding a theory column refuses to pose as finite`() {
        val spec = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(3), null, null),
            factors = emptyArray(),
        )
        val problem = spec.materialize(MixedIntColumns(arrayOf(IntColumn.Bounded(0, 3))))

        assertNull(problem.intColumns.allFiniteOrNull())
        assertFailsWith<IllegalArgumentException> { problem.requireFiniteIntDomains() }
    }
}
