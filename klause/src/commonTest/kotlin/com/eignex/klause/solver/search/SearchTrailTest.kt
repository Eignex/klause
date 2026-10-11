package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SearchTrailTest {
    @Test
    fun `rebuilding the root discards facts from the previous seed`() {
        val session = SearchSession(emptyList())
        session.publish(SearchDecision.Bool(0))
        session.publish(SearchDecision.IntAtLeast(0, 4L))
        session.publish(SearchDecision.IntAtMost(0, 9L))
        assertIs<ComponentResult.Consistent>(session.propagate())

        session.resetRootFacts()

        assertNull(session.boolValue(0))
        assertNull(session.intLowerBound(0))
        assertNull(session.intUpperBound(0))
        assertEquals(0, session.rootFixedCount)
        session.publish(SearchDecision.IntEqual(0, 2L))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertEquals(2L, session.intLowerBound(0))
        assertEquals(2L, session.intUpperBound(0))
    }

    @Test
    fun `rebuilding root facts retains and wakes learned units`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertEquals(true, session.boolValue(0))

        session.resetRootFacts()
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(1, session.learnedClauseCount)
        assertEquals(true, session.boolValue(0))
        assertEquals(1, session.rootFixedCount)
    }
}
