package com.eignex.klause.bench.profile

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NumericalProductionAggregateSnapshotTest {
    @Test
    fun `finite rational points should use the rounded production projection`() {
        val x = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val point = mapOf(x to BigFraction.ofLong(49) / BigFraction.ofLong(20))

        val projected = NumericalProductionAggregateSnapshot(Cancellation.Never).project(point)

        assertEquals(mapOf(x to 2.45), projected)
    }

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
