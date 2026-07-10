package com.eignex.klause.backtrack.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wall-clock LP circuit breaker. Verified in isolation (the wall-clock measurement lives at the call
 * site, so the trip logic is a pure function of charged millis + solve count + prune flags). It must
 * disable an expensive LP that spends its budget while still starved of solves, spare one that ever
 * prunes or reaches the ladder's warmup, stay off when no budget is set, latch the trip, and report the
 * remaining budget for time-boxing root work.
 */
class LpWallBreakerTest {

    @Test
    fun `an expensive unproductive LP trips once it spends the budget under the warmup`() {
        val breaker = LpWallBreaker(budgetMillis = 100, warmupSolves = 64)
        breaker.charge(millis = 60, pruned = false)
        assertFalse(breaker.isTripped, "under budget, still enabled")
        breaker.charge(millis = 60, pruned = false)
        assertTrue(breaker.isTripped, "budget spent in a couple of expensive solves disables the LP")
    }

    @Test
    fun `a cheap LP that reaches the warmup first is left to the ladder`() {
        val breaker = LpWallBreaker(budgetMillis = 100, warmupSolves = 8)
        // Eight cheap solves reach the warmup well before the budget; the ladder now owns the decision.
        repeat(8) { breaker.charge(millis = 5, pruned = false) }
        assertFalse(breaker.isTripped, "warmup reached before the budget spares the LP")
        breaker.charge(millis = 1_000, pruned = false)
        assertFalse(breaker.isTripped, "past the warmup the breaker never trips, however long the LP runs")
    }

    @Test
    fun `an LP that ever prunes is never tripped`() {
        val breaker = LpWallBreaker(budgetMillis = 100, warmupSolves = 64)
        breaker.charge(millis = 50, pruned = true)
        breaker.charge(millis = 1_000, pruned = false)
        assertFalse(breaker.isTripped, "a prune lifts the budget's grip permanently")
    }

    @Test
    fun `a zero budget disables the breaker`() {
        val breaker = LpWallBreaker(budgetMillis = 0, warmupSolves = 64)
        breaker.charge(millis = 10_000, pruned = false)
        assertFalse(breaker.isTripped, "no budget means the prior unbounded behaviour")
        assertNull(breaker.remainingMillis(), "no budget reports no remaining cap")
    }

    @Test
    fun `remaining budget shrinks with charges and floors at zero`() {
        val breaker = LpWallBreaker(budgetMillis = 100, warmupSolves = 64)
        assertEquals(100, breaker.remainingMillis())
        breaker.charge(millis = 30, pruned = false)
        assertEquals(70, breaker.remainingMillis())
        breaker.charge(millis = 200, pruned = false)
        assertEquals(0, breaker.remainingMillis(), "an overspend floors the remaining budget at zero")
    }

    @Test
    fun `the trip latches even after a later prune`() {
        val breaker = LpWallBreaker(budgetMillis = 100, warmupSolves = 64)
        breaker.charge(millis = 120, pruned = false)
        assertTrue(breaker.isTripped)
        breaker.charge(millis = 10, pruned = true)
        assertTrue(breaker.isTripped, "a prune after the trip does not re-enable the LP")
    }
}
