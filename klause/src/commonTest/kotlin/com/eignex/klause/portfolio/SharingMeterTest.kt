package com.eignex.klause.portfolio

import com.eignex.klause.solver.result.ChannelTraffic
import com.eignex.klause.solver.result.SharingChannel
import kotlin.test.Test
import kotlin.test.assertEquals

class SharingMeterTest {

    @Test
    fun `a snapshot reports each channel's traffic and leaves out channels without any`() {
        val meter = SharingMeter()
        meter.exported(SharingChannel.Clauses, 3)
        meter.imported(SharingChannel.Clauses, 2)
        meter.duplicates(SharingChannel.Clauses, 1)
        meter.charge(SharingChannel.Clauses, 500L)

        val snapshot = meter.snapshot()

        assertEquals(mapOf(SharingChannel.Clauses to ChannelTraffic(500L, 3L, 2L, 1L)), snapshot.channels)
    }
}
