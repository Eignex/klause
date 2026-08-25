package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Whether the General LIA lane admits a system, and what witness box it would search, are two questions
 * with very different costs: the box raises `m·a` to the `2m+1`, so it is a multi-megabyte integer on a
 * model with a few hundred thousand rows. Routing asks only the first, so the two must agree on which
 * systems are in the fragment.
 */
class SmallModelBoundTest {

    private val openBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null)

    private fun row(vararg vars: Int) = Linear(LongArray(vars.size) { 1L }, vars, LinearOp.LE, 5L)

    @Test
    fun `admissibility agrees with the bound on a system in the fragment`() {
        val factors = listOf<Factor>(row(0, 1), row(1, 2))

        assertEquals(true, admitsSmallModelBound(3, factors, openBounds))
        assertNotNull(smallModelBigIntBound(3, factors, openBounds))
    }

    @Test
    fun `admissibility agrees with the bound on a factor outside the fragment`() {
        // A value-indexing global is not a linear row, so the theorem does not cover the system.
        val factors = listOf<Factor>(row(0, 1), AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4))

        assertEquals(false, admitsSmallModelBound(2, factors, openBounds))
        assertNull(smallModelBigIntBound(2, factors, openBounds))
    }

    @Test
    fun `a system with no rows is admitted and bounded at one`() {
        assertEquals(true, admitsSmallModelBound(0, emptyList(), openBounds))
        assertEquals("1", smallModelBigIntBound(0, emptyList(), openBounds).toString())
    }
}
