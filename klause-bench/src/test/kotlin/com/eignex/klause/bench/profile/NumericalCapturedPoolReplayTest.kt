package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class NumericalCapturedPoolReplayTest {
    @Test
    fun `missing verified proposals should decline after resolving captured replay interfaces`() {
        val records = mutableListOf<JsonObject>()

        NumericalCapturedPoolReplay(Cancellation.Never).replay(Any(), emptyList(), records::add)

        assertEquals("no-fresh-checked-proposals", records.single().getValue("state").jsonPrimitive.content)
        assertEquals("0", records.single().getValue("lpQueries").jsonPrimitive.content)
    }
}
