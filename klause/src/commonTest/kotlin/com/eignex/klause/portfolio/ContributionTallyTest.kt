package com.eignex.klause.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

class ContributionTallyTest {

    private fun drained(tally: ContributionTally): Map<Pair<Contribution, Int>, Long> {
        val out = HashMap<Pair<Contribution, Int>, Long>()
        tally.drain { kind, origin, uses -> out[kind to origin] = uses }
        return out
    }

    @Test
    fun `uses add up per kind and origin`() {
        val tally = ContributionTally()
        tally.note(Contribution.Clause, 2, 3)
        tally.note(Contribution.Clause, 2, 1)
        tally.note(Contribution.Cut, 0)

        assertEquals(mapOf((Contribution.Clause to 2) to 4L, (Contribution.Cut to 0) to 1L), drained(tally))
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
