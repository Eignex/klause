package com.eignex.klause.solver.incumbent

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class IncumbentExchangeTest {
    @Test
    fun `private offers should coalesce and remain invisible until the boundary check`() {
        val checked = mutableListOf<String>()
        val events = mutableListOf<IncumbentOfferEvent>()
        val exchange = IncumbentExchange<String, Int>({ a, b -> a < b }, { candidate ->
            checked += candidate.assignment
            Verification.Accepted(candidate)
        })
        val subscription = exchange.subscribe()

        exchange.withBufferedOffers(Cancellation.Never, events::add, { it() }) {
            exchange.offerFromWorker("first", 4) { it() }
            exchange.offerFromWorker("best", 2) { it() }
            exchange.offerFromWorker("later", 3) { it() }
            assertNull(subscription.poll())
            assertEquals(emptyList(), checked)
        }

        assertEquals(listOf("best"), checked)
        assertEquals("best", subscription.poll()?.assignment)
        assertEquals(mapOf(IncumbentOfferEvent.Segments to 1, IncumbentOfferEvent.Offered to 3,
            IncumbentOfferEvent.Coalesced to 2, IncumbentOfferEvent.Checked to 1,
            IncumbentOfferEvent.Installed to 1), events.groupingBy { it }.eachCount())
    }

    @Test
    fun `refuted and undecided buffered points should leave the verified incumbent intact`() {
        for (refuted in listOf(false, true)) {
            val events = mutableListOf<IncumbentOfferEvent>()
            val exchange = IncumbentExchange<String, Int>({ a, b -> a < b }, { candidate ->
                when {
                    candidate.assignment == "standing" -> Verification.Accepted(candidate)
                    refuted -> Verification.Rejected("refuted")
                    else -> Verification.Indeterminate("unavailable")
                }
            })
            exchange.offer("standing", 5)

            exchange.withBufferedOffers(Cancellation.Never, events::add, { it() }) {
                exchange.offerFromWorker("proposal", 1) { it() }
            }

            assertEquals("standing", exchange.current()?.assignment)
            assertEquals(if (refuted) IncumbentOfferEvent.Rejected else IncumbentOfferEvent.Indeterminate,
                events.last())
        }
    }

    @Test
    fun `public offers should verify immediately within a private buffering scope`() {
        val checked = mutableListOf<String>()
        val events = mutableListOf<IncumbentOfferEvent>()
        val exchange = IncumbentExchange<String, Int>({ a, b -> a < b }, { candidate ->
            checked += candidate.assignment
            Verification.Accepted(candidate)
        })

        exchange.withBufferedOffers(Cancellation.Never, events::add, { it() }) {
            exchange.offerFromWorker("queued", 3) { it() }
            assertIs<Publication.Installed<String, Int>>(exchange.offer("public", 1))
            assertEquals(listOf("public"), checked)
            assertEquals("public", exchange.current()?.assignment)
        }

        assertEquals(listOf("public", "queued"), checked)
        assertEquals(IncumbentOfferEvent.NotImproving, events.last())
        assertEquals("public", exchange.current()?.assignment)
    }

    @Test
    fun `failed queries should discard pending offers and restore immediate worker publication`() {
        val events = mutableListOf<IncumbentOfferEvent>()
        val exchange = minimizing()

        assertFailsWith<IllegalStateException> {
            exchange.withBufferedOffers(Cancellation.Never, events::add, { it() }) {
                exchange.offerFromWorker("discard", 1.0) { it() }
                error("query failed")
            }
        }
        assertNull(exchange.current())
        exchange.offerFromWorker("fresh", 2.0) { it() }

        assertEquals(IncumbentOfferEvent.Dropped, events.last())
        assertEquals("fresh", exchange.current()?.assignment)
    }

    @Test
    fun `expiration before or during buffered verification should withhold installation`() {
        for (duringCheck in listOf(false, true)) {
            var expired = false
            var checks = 0
            val events = mutableListOf<IncumbentOfferEvent>()
            val exchange = IncumbentExchange<String, Int>({ a, b -> a < b }, { candidate ->
                checks++
                expired = true
                Verification.Accepted(candidate)
            })

            exchange.withBufferedOffers(Cancellation { expired }, events::add, { it() }) {
                exchange.offerFromWorker("expired", 1) { it() }
                expired = !duringCheck
            }

            assertNull(exchange.current())
            assertEquals(if (duringCheck) 1 else 0, checks)
            assertEquals(1, events.count { it == IncumbentOfferEvent.Expired })
            assertEquals(if (duringCheck) 0 else 1, events.count { it == IncumbentOfferEvent.Dropped })
        }
    }

    @Test
    fun `a failed buffered verifier should release the pending point before the next query`() {
        val exchange = IncumbentExchange<String, Int>({ a, b -> a < b }, { candidate ->
            check(candidate.assignment != "bad")
            Verification.Accepted(candidate)
        })
        val events = mutableListOf<IncumbentOfferEvent>()

        assertFailsWith<IllegalStateException> {
            exchange.withBufferedOffers(Cancellation.Never, events::add, { it() }) {
                exchange.offerFromWorker("bad", 1) { it() }
            }
        }
        exchange.withBufferedOffers(Cancellation.Never, events::add, { it() }) {
            exchange.offerFromWorker("good", 2) { it() }
        }

        assertEquals("good", exchange.current()?.assignment)
        assertEquals(1, events.count { it == IncumbentOfferEvent.Failed })
        assertEquals(2, events.count { it == IncumbentOfferEvent.Checked })
    }

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
