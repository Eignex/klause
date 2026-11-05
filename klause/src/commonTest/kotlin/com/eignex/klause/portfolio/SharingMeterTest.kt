package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.ChannelTraffic
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.solver.incumbent.IncumbentOfferEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class SharingMeterTest {
    @Test
    fun `incumbent exports should count installations separately from queued offers`() {
        val meter = SharingMeter()
        for (event in listOf(IncumbentOfferEvent.Offered, IncumbentOfferEvent.Offered,
            IncumbentOfferEvent.Coalesced, IncumbentOfferEvent.Checked, IncumbentOfferEvent.Installed)) {
            meter.incumbentEvent(event)
        }

        val snapshot = meter.snapshot()

        assertEquals(mapOf("offered" to 2L, "coalesced" to 1L, "checked" to 1L, "installed" to 1L),
            snapshot.incumbents.filterValues { it > 0L })
        assertEquals(0L, snapshot.incumbents.getValue("dropped"))
        assertEquals(0L, snapshot.incumbents.getValue("expired"))
        assertEquals(1L, snapshot.channels.getValue(SharingChannel.Incumbents).exported)
        assertEquals(snapshot.incumbents, snapshot.copy(channels = emptyMap()).incumbents)
        assertEquals(snapshot.incumbents, snapshot.copy(clauses = mapOf("sample" to 1L)).incumbents)
    }

    @Test
    fun `a snapshot should report each channel's traffic and omit idle channels`() {
        val meter = SharingMeter()
        meter.exported(SharingChannel.Clauses, 3)
        meter.imported(SharingChannel.Clauses, 2)
        meter.duplicates(SharingChannel.Clauses, 1)
        meter.charge(SharingChannel.Clauses, 500L)

        val snapshot = meter.snapshot()

        assertEquals(mapOf(SharingChannel.Clauses to ChannelTraffic(500L, 3L, 2L, 1L)), snapshot.channels)
    }
}
