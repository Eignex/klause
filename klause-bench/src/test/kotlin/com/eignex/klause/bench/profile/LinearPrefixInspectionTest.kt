package com.eignex.klause.bench.profile

import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LinearPrefixInspectionTest {
    @Test
    fun `shared premise chains retain their graph size and asserted fact`() {
        var premise: SearchAtomPremise = SearchAtomPremise.Asserted(SearchDecision.Bool(6))
        repeat(24) { premise = SearchAtomPremise.All(listOf(premise, premise)) }
        val snapshot = LinearPrefixSnapshot(Cancellation.Never)

        val root = snapshot.record(premise)
        val graph = snapshot.premiseGraph()

        assertEquals("0", root.jsonObject.getValue("premise").jsonPrimitive.content)
        assertEquals(25, graph.size)
        for (node in graph.dropLast(1)) {
            val children = node.jsonObject.getValue("children").jsonArray
            assertEquals(children[0], children[1])
        }
        val leaf = graph.last().jsonObject
        assertEquals("asserted", leaf.getValue("type").jsonPrimitive.content)
        assertEquals("6", leaf.getValue("decision").jsonObject.getValue("literal").jsonPrimitive.content)
    }
}
