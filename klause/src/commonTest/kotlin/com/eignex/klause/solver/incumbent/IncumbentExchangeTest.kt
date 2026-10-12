package com.eignex.klause.solver.incumbent

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncumbentExchangeTest {
    @Test
    fun `published samples keep their verified objective after external mutation`() {
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
    fun `a strictly better candidate is installed`() {
        val exchange = minimizing()
        exchange.offer("a", 5.0)
        val publication = assertIs<Publication.Installed<String, Double>>(exchange.offer("b", 3.0))
        assertEquals("b", publication.incumbent.assignment)
        assertEquals(3.0, exchange.current()?.objective)
    }

    @Test
    fun `a candidate no better than the incumbent is not installed`() {
        val exchange = minimizing()
        exchange.offer("a", 3.0)
        assertIs<Publication.NotImproving>(exchange.offer("b", 3.0), "equal is not strictly better")
        assertIs<Publication.NotImproving>(exchange.offer("c", 4.0))
        assertEquals("a", exchange.current()?.assignment)
    }

    @Test
    fun `only an installation advances the version`() {
        val exchange = minimizing()
        exchange.offer("a", 5.0)
        exchange.offer("b", 4.0)
        exchange.offer("c", 4.0)
        assertEquals(2L, exchange.current()?.version, "two installations, one rejected tie")
    }

    @Test
    fun `the exchange is empty until the first installation`() {
        assertNull(minimizing().current())
    }

    @Test
    fun `a non-finite objective is rejected`() {
        val exchange = minimizing()
        assertIs<Publication.Rejected>(exchange.offer("a", Double.POSITIVE_INFINITY))
        assertIs<Publication.Rejected>(exchange.offer("b", Double.NaN))
        assertNull(exchange.current(), "a score carrying no information never becomes an incumbent")
    }

    @Test
    fun `a rejected candidate leaves the standing incumbent alone`() {
        val exchange = IncumbentExchange<String, Double>(
            improves = { candidate, standing -> candidate < standing },
            verifier = { candidate ->
                if (candidate.assignment == "bad") Verification.Rejected("bad") else Verification.Accepted(candidate)
            },
        )
        exchange.offer("good", 5.0)
        val publication = assertIs<Publication.Rejected>(exchange.offer("bad", 1.0))
        assertEquals("bad", publication.reason)
        assertEquals("good", exchange.current()?.assignment, "rejection refutes the candidate, not the incumbent")
    }

    @Test
    fun `an undecided candidate is neither installed nor rejected`() {
        val exchange = IncumbentExchange<String, Double>(
            improves = { candidate, standing -> candidate < standing },
            verifier = { Verification.Indeterminate("budget exhausted") },
        )
        val publication = assertIs<Publication.Indeterminate>(exchange.offer("a", 1.0))
        assertEquals("budget exhausted", publication.reason)
        assertNull(exchange.current())
    }

    @Test
    fun `an exchange maximises when improvement is reversed`() {
        val exchange = IncumbentExchange<String, Int>(improves = { candidate, standing -> candidate > standing })
        exchange.offer("a", 1)
        exchange.offer("b", 7)
        exchange.offer("c", 3)
        assertEquals("b", exchange.current()?.assignment, "the comparator, not the type, fixes the direction")
    }

    @Test
    fun `a subscription hands back each incumbent once`() {
        val exchange = minimizing()
        val subscription = exchange.subscribe()
        assertNull(subscription.poll(), "nothing to import from an empty exchange")
        exchange.offer("a", 5.0)
        assertEquals("a", subscription.poll()?.assignment)
        assertNull(subscription.poll(), "the same version is not handed out twice")
        exchange.offer("a", 4.0)
        assertEquals("a", subscription.poll()?.assignment, "a re-published assignment at a new version is fresh")
    }

    @Test
    fun `subscriptions track their own position`() {
        val exchange = minimizing()
        val early = exchange.subscribe()
        exchange.offer("a", 5.0)
        early.poll()
        exchange.offer("b", 4.0)
        val late = exchange.subscribe()
        assertEquals("b", late.poll()?.assignment, "a fresh subscription starts from whatever stands")
        assertEquals("b", early.poll()?.assignment, "and does not consume it for the older one")
    }

    @Test
    fun `a verifier sees the candidate it was offered`() {
        val seen = mutableListOf<Candidate<String, Double>>()
        val exchange = IncumbentExchange<String, Double>(
            improves = { candidate, standing -> candidate < standing },
            verifier = { candidate ->
                seen += candidate
                Verification.Accepted(candidate)
            },
        )
        exchange.offer("a", 2.0)
        assertEquals(listOf(Candidate("a", 2.0)), seen)
    }

    @Test
    fun `a trusting verifier accepts every candidate`() {
        val verifier = CandidateVerifier.trusting<String, Double>()
        val verdict = assertIs<Verification.Accepted<String, Double>>(verifier.verify(Candidate("a", Double.NaN)))
        assertTrue(verdict.candidate.assignment == "a")
    }
}
