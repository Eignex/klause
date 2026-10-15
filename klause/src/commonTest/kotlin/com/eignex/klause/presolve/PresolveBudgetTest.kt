package com.eignex.klause.presolve

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveBudgetTest {
    @Test
    fun `a slice should fire once its share of the budget is charged`() {
        val budget = PresolveBudget(1000)
        val slice = budget.slice(400)

        assertFalse(slice())
        budget.charge(300)
        assertFalse(slice(), "300 of the 400 share charged")
        budget.charge(100)
        assertTrue(slice())
    }

    @Test
    fun `a slice should fire when the whole budget runs out before its share does`() {
        val budget = PresolveBudget(300)
        val slice = budget.slice(1000)

        assertFalse(slice())
        budget.charge(300)
        assertTrue(slice())
    }

    @Test
    fun `an unspent share should be left in the pool for the next slice`() {
        val budget = PresolveBudget(1000)
        val first = budget.slice(500)
        budget.charge(100)
        assertFalse(first())

        val second = budget.slice(budget.remaining())
        budget.charge(800)
        assertFalse(second(), "the second slice was taken from the full 900 that was left")
        budget.charge(100)
        assertTrue(second())
    }

    @Test
    fun `work charged through a composed slice should land in the budget`() {
        val budget = PresolveBudget(1000)
        val token = budget.slice(500) or Cancellation.Never

        token.charge(200)

        assertEquals(800L, budget.remaining())
    }

    @Test
    fun `the phase token should fire once its budget is spent`() {
        val budget = PresolveBudget(100)
        val phase = budget.orSpent(Cancellation.Never)
        assertSame(budget.slice(100).workMeter(), phase.workMeter())

        phase.charge(100)

        assertTrue(phase())
    }

    @Test
    fun `the phase token should fire when the outer token does`() {
        assertTrue(PresolveBudget(100).orSpent(Cancellation { true })())
    }
}
