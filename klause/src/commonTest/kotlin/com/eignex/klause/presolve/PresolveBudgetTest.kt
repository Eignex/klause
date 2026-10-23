package com.eignex.klause.presolve

import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PresolveBudgetTest {
    @Test
    fun `direct stop observation should reuse only its owning budget token`() {
        val budget = PresolveBudget(100)
        val other = PresolveBudget(100)
        val token = budget.observeStops(Cancellation { true })
        val first = PresolveInvocation("first", PresolveLane.SOURCE, PresolveSizeStats(0, 0, 0))
        val second = PresolveInvocation("second", PresolveLane.SOURCE, PresolveSizeStats(0, 0, 0))
        budget.activeInvocation = first
        assertTrue(budget.observeStops(token)())
        assertTrue(first.interrupted)
        first.interrupted = false
        budget.activeInvocation = second
        other.activeInvocation = first

        assertSame(token, budget.observeStops(token))
        assertTrue(other.observeStops(token)())
        assertTrue(first.interrupted)
        assertTrue(second.interrupted)
    }

    @Test
    fun `stop observation should retain newer predicates with the same meter`() {
        val budget = PresolveBudget(100)
        var stopped = false
        val token = budget.slice(50) or Cancellation { stopped }
        val observed = budget.observeStops(token)

        assertFalse(observed())
        stopped = true

        assertTrue(observed())
        assertEquals(100L, budget.remaining())
    }

    @Test
    fun `stop observation should retain a newer slice allowance`() {
        val budget = PresolveBudget(100)
        val original = budget.slice(80)
        val observed = budget.observeStops(original or budget.slice(10))

        observed.charge(10)

        assertFalse(original())
        assertTrue(observed())
        assertEquals(90L, budget.remaining())
    }

    @Test
    fun `stop observation should forward the deadline meter and ordered predicates`() {
        val budget = PresolveBudget(100)
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        var work = 0L
        var laterCalls = 0
        val meter = WorkMeter { work += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = true
            override fun deadline() = deadline
            override fun workMeter() = meter
        } or Cancellation { laterCalls++; false }
        val observed = budget.observeStops(budget.observeStops(token))

        observed.charge(7L)

        assertTrue(observed())
        assertEquals(0, laterCalls)
        assertEquals(deadline, observed.deadline())
        assertSame(meter, observed.workMeter())
        assertEquals(7L, work)
        assertEquals(0L, budget.spent())
    }

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
