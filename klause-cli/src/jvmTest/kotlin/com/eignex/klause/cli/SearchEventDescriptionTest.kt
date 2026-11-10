package com.eignex.klause.cli

import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.search.SearchComponentStop
import com.eignex.klause.solver.search.SearchStopCause
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SearchEventDescriptionTest(private val worker: String?) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun workers(): List<String?> = listOf(null, "bt/lp-default")
    }

    @Test
    fun `an incomplete event without component history should retain its return observation`() {
        val event = SearchEvent.BacktrackIncomplete(SearchEvent.BacktrackIncomplete.Path.Component, null)

        val text = describeEvent(event, 7L, worker)

        assertEquals(
            "[7 ms]${worker?.let { " $it" }.orEmpty()} incomplete optimization: path=Component stop=null", text,
        )
    }

    @Test
    fun `an incomplete event should distinguish its first stop from cached consumption`() {
        val event = SearchEvent.BacktrackIncomplete(
            SearchEvent.BacktrackIncomplete.Path.Component, null,
            SearchComponentStop(SearchComponentStop.Site.ModelBlock, SearchStopCause.Cancelled,
                cancelled = true, unresolvedCoverage = false, decisionLevel = 0),
            componentStopReplayed = true,
        )

        val text = describeEvent(event, 7L, worker)

        assertEquals(
            "[7 ms]${worker?.let { " $it" }.orEmpty()} incomplete optimization: path=Component stop=null" +
                " origin=ModelBlock firstStop=Cancelled cancelled=true unresolved=false level=0 replayed=true",
            text,
        )
    }
}
