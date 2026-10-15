package com.eignex.klause.solver.incumbent

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IncumbentExchangeTest {
    @Test
    fun `published sample should keep its verified objective after external mutation`() {
        val values = longArrayOf(3)
        val objective = LinearObjective(intCoefficients = longArrayOf(2))
        val sample = Sample(BooleanArray(0), values)
        val exchange = IncumbentExchange<Sample, Long>(
            improves = { candidate, standing -> candidate < standing },
            verifier = { candidate ->
                if (objective.evaluateLong(candidate.assignment) == candidate.objective) {
                    Verification.Accepted(candidate)
                } else {
                    Verification.Rejected("objective differs")
                }
            },
        )
        exchange.offer(sample, 6)
        val published = checkNotNull(exchange.current())

        values[0] = 0
        published.assignment.ints[0] = 0

        assertEquals(published.objective, objective.evaluateLong(checkNotNull(exchange.current()).assignment))
        assertEquals(3L, checkNotNull(exchange.current()).assignment.intValue(0))
    }

    private fun minimizing() = IncumbentExchange.minimizing<String>()

    @Test
    fun `offer should install a strictly better candidate`() {
        val exchange = minimizing()
        exchange.offer("a", 5.0)
        val publication = assertIs<Publication.Installed<String, Double>>(exchange.offer("b", 3.0))
        assertEquals("b", publication.incumbent.assignment)
        assertEquals(3.0, exchange.current()?.objective)
    }

    @Test
    fun `offer should not install a candidate that is not strictly better`() {
        val exchange = minimizing()
        exchange.offer("a", 3.0)
        assertIs<Publication.NotImproving>(exchange.offer("b", 3.0))
        assertIs<Publication.NotImproving>(exchange.offer("c", 4.0))
        assertEquals("a", exchange.current()?.assignment)
    }

    @Test
    fun `version should advance only on installation`() {
        val exchange = minimizing()
        exchange.offer("a", 5.0)
        exchange.offer("b", 4.0)
        exchange.offer("c", 4.0)
        assertEquals(2L, exchange.current()?.version)
    }

    @Test
    fun `offer should reject a non-finite objective`() {
        val exchange = minimizing()
        assertIs<Publication.Rejected>(exchange.offer("a", Double.POSITIVE_INFINITY))
        assertIs<Publication.Rejected>(exchange.offer("b", Double.NaN))
        assertNull(exchange.current())
    }

    @Test
    fun `rejected candidate should leave the incumbent unchanged`() {
        val exchange = IncumbentExchange<String, Double>(
            improves = { candidate, standing -> candidate < standing },
            verifier = { candidate ->
                if (candidate.assignment == "bad") Verification.Rejected("bad") else Verification.Accepted(candidate)
            },
        )
        exchange.offer("good", 5.0)
        val publication = assertIs<Publication.Rejected>(exchange.offer("bad", 1.0))
        assertEquals("bad", publication.reason)
        assertEquals("good", exchange.current()?.assignment)
    }

    @Test
    fun `indeterminate candidate should be neither installed nor rejected`() {
        val exchange = IncumbentExchange<String, Double>(
            improves = { candidate, standing -> candidate < standing },
            verifier = { Verification.Indeterminate("budget exhausted") },
        )
        val publication = assertIs<Publication.Indeterminate>(exchange.offer("a", 1.0))
        assertEquals("budget exhausted", publication.reason)
        assertNull(exchange.current())
    }

    @Test
    fun `exchange should maximize with a reversed comparator`() {
        val exchange = IncumbentExchange<String, Int>(improves = { candidate, standing -> candidate > standing })
        exchange.offer("a", 1)
        exchange.offer("b", 7)
        exchange.offer("c", 3)
        assertEquals("b", exchange.current()?.assignment)
    }

    @Test
    fun `subscription should return each incumbent version once`() {
        val exchange = minimizing()
        val subscription = exchange.subscribe()
        assertNull(subscription.poll())
        exchange.offer("a", 5.0)
        assertEquals("a", subscription.poll()?.assignment)
        assertNull(subscription.poll())
        exchange.offer("a", 4.0)
        assertEquals("a", subscription.poll()?.assignment)
    }

    @Test
    fun `subscriptions should track their own position`() {
        val exchange = minimizing()
        val early = exchange.subscribe()
        exchange.offer("a", 5.0)
        early.poll()
        exchange.offer("b", 4.0)
        val late = exchange.subscribe()
        assertEquals("b", late.poll()?.assignment)
        assertEquals("b", early.poll()?.assignment)
    }
}
