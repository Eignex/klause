package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NumericalProductionAggregateSnapshotTest {
    @Test
    fun `missing paid points should decline after resolving production interfaces`() {
        val journal = NumericalSelectionSnapshot(Cancellation.Never).journal()
        val records = mutableListOf<JsonObject>()

        NumericalProductionAggregateSnapshot(Cancellation.Never).inspect(journal, 9, records::add)

        assertEquals("requested-point-generation-context-or-installed-pool-unavailable",
            records.first().getValue("state").jsonPrimitive.content)
        assertEquals("0", records.last().getValue("generationCalls").jsonPrimitive.content)
        assertEquals("0", records.last().getValue("releasedGenerationContextReferences").jsonPrimitive.content)
        assertEquals("0", records.last().getValue("lpQueries").jsonPrimitive.content)
    }
}
