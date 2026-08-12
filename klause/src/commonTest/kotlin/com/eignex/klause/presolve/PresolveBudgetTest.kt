package com.eignex.klause.presolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The presolve phase's remaining-time budget and the per-pass slices taken from it. A slice is defined
 * against [PresolveBudget.remaining] rather than a clock, so these drive it from a settable counter.
 */
class PresolveBudgetTest {

    private var left = 0L
    private val budget = PresolveBudget { left }

    @Test
    fun `a slice fires once its share of the budget is spent`() {
        left = 1000
        val slice = budget.slice(400)

        assertFalse(slice(), "nothing spent yet")
        left = 700
        assertFalse(slice(), "300 of the 400 share spent")
        left = 600
        assertTrue(slice(), "the share is spent")
    }

    @Test
    fun `a slice fires when the whole budget runs out before its share does`() {
        left = 300
        val slice = budget.slice(1000)

        assertFalse(slice())
        left = 0
        assertTrue(slice(), "the phase budget is gone, so the slice cannot continue")
    }

    @Test
    fun `an unspent share is left in the pool for the next slice`() {
        // The point of slicing against remaining rather than handing out fixed quanta: a pass that
        // returns early does not burn its allowance.
        left = 1000
        val first = budget.slice(500)
        left = 900
        assertFalse(first(), "the first pass used only 100")

        val second = budget.slice(budget.remaining())
        left = 100
        assertFalse(second(), "the second slice was taken from the full 900 that was left")
        left = 0
        assertTrue(second())
    }

    @Test
    fun `a zero share is already spent`() {
        left = 1000
        assertTrue(budget.slice(0)(), "a pass with no share left must not start")
    }

    @Test
    fun `remaining never reports a negative budget`() {
        left = -50
        assertEquals(0L, budget.remaining())
    }
}
