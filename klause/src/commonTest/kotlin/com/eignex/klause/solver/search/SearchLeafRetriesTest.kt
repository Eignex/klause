package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchLeafRetriesTest {
    @Test
    fun `an already retained prefix should remain admitted at the retention limit`() {
        val session = SearchSession(emptyList())
        session.initialize()
        session.push(SearchDecision.Bool(1))
        val retries = SearchLeafRetries(session, { 0 }, { true }, { true }, { false },
            { SearchRunEvent.Indeterminate.Cancelled }, SearchRunObserver.None, { error("unexpected replay") },
            maximumLeaves = 1, maximumDecisions = 1)

        assertTrue(retries.retain())
        assertTrue(retries.retain())
        session.popTo(0)
        session.push(SearchDecision.Bool(0))

        assertFalse(retries.retain())
    }
}
