package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

class ContributionTallyTest {

    private fun drained(tally: ContributionTally): Map<Pair<Contribution, Int>, Double> {
        val out = HashMap<Pair<Contribution, Int>, Double>()
        tally.drain { kind, origin, amount -> out[kind to origin] = amount }
        return out
    }

    @Test
    fun `uses add up per kind and origin`() {
        val tally = ContributionTally()
        tally.note(Contribution.Clause, 2, 3.0)
        tally.note(Contribution.Clause, 2, 1.0)
        tally.note(Contribution.Cut, 0)

        assertEquals(mapOf((Contribution.Clause to 2) to 4.0, (Contribution.Cut to 0) to 1.0), drained(tally))
    }

    @Test
    fun `a use naming no origin is ignored`() {
        val tally = ContributionTally()

        tally.note(Contribution.Bound, -1)

        assertEquals(emptyMap(), drained(tally))
    }

    @Test
    fun `draining resets the counts`() {
        val tally = ContributionTally()
        tally.note(Contribution.Bound, 1)
        drained(tally)

        assertEquals(emptyMap(), drained(tally))
    }
}
