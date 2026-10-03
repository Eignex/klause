package com.eignex.klause.presolve

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The presolve phase's work allowance and the per-pass slices taken from it, driven by explicit charges. */
class PresolveBudgetTest {

    @Test
    fun `a slice fires once its share of the budget is charged`() {
        val budget = PresolveBudget(1000)
        val slice = budget.slice(400)

        assertFalse(slice(), "nothing charged yet")
        budget.charge(300)
        assertFalse(slice(), "300 of the 400 share charged")
        budget.charge(100)
        assertTrue(slice(), "the share is charged")
    }

    @Test
    fun `a slice fires when the whole budget runs out before its share does`() {
        val budget = PresolveBudget(300)
        val slice = budget.slice(1000)

        assertFalse(slice())
        budget.charge(300)
        assertTrue(slice(), "the phase budget is gone, so the slice cannot continue")
    }

    @Test
    fun `an unspent share is left in the pool for the next slice`() {
        // The point of slicing against remaining rather than handing out fixed quanta: a pass that
        // returns early does not burn its allowance.
        val budget = PresolveBudget(1000)
        val first = budget.slice(500)
        budget.charge(100)
        assertFalse(first(), "the first pass used only 100")

        val second = budget.slice(budget.remaining())
        budget.charge(800)
        assertFalse(second(), "the second slice was taken from the full 900 that was left")
        budget.charge(100)
        assertTrue(second())
    }

    @Test
    fun `a zero share is already spent`() {
        assertTrue(PresolveBudget(1000).slice(0)(), "a pass with no share left must not start")
    }

    @Test
    fun `remaining never reports a negative budget`() {
        val budget = PresolveBudget(100)

        budget.charge(150)

        assertEquals(0L, budget.remaining())
    }

    @Test
    fun `work charged through a slice composed with another token lands in the budget`() {
        val budget = PresolveBudget(1000)
        val token = budget.slice(500) or Cancellation.Never

        token.charge(200)

        assertEquals(800L, budget.remaining())
    }

    @Test
    fun `the phase token charges its budget and fires once the budget is spent`() {
        val budget = PresolveBudget(100)
        val phase = budget.orSpent(Cancellation.Never)
        assertSame(budget, phase.workMeter())

        phase.charge(100)

        assertTrue(phase())
    }

    @Test
    fun `the phase token fires when the outer token does`() {
        assertTrue(PresolveBudget(100).orSpent(Cancellation { true })())
    }
}
