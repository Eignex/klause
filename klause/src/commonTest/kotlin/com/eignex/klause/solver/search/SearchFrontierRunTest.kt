package com.eignex.klause.solver.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchFrontierRunTest {
    @Test
    fun `frontier should resume in bound order without dropping siblings`() {
        val session = SearchSession(emptyList())
        val visited = ArrayList<String>()
        var released = false
        session.initialize()
        val policy = object : SearchNodePolicy {
            override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
                val left = context.boolValue(0) ?: return SearchNodeDisposition.Expand
                val right = context.boolValue(1) ?: return SearchNodeDisposition.Expand
                visited += "${if (left) 1 else 0}${if (right) 1 else 0}"
                return SearchNodeDisposition.Prune
            }
        }
        val run = SearchFrontierRun(session, SearchSolveParams(), BooleanBranching.SourceOrder(2), policy,
            SearchDecisionBudget.Unlimited, { when (session.boolValue(0)) {
                false -> 10
                true -> 1
                null -> 0
            } }, Comparator<Int> { left, right -> left.compareTo(right) }, { visited.size == 1 && !released })

        assertIs<SearchRunEvent.Paused>(run.next())
        released = true
        val result = run.next()

        assertIs<SearchRunEvent.Exhausted>(result)
        assertEquals(listOf("10", "11", "00", "01"), visited)
    }
}
