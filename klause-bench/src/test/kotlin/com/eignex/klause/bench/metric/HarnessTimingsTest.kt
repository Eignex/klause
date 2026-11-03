package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HarnessTimingsTest {
    @Test
    fun `nested failed phases retain inclusive durations without losing submillisecond accumulation`() {
        var nanos = 0L
        val timings = HarnessTimings { nanos }

        assertFailsWith<IllegalStateException> {
            timings.measure("outerMs") {
                repeat(2) {
                    timings.measure("innerMs") { nanos += 600_000 }
                }
                nanos += 800_000
                error("failed")
            }
        }

        assertEquals(mapOf("innerMs" to "1", "outerMs" to "2"), timings.stats())
    }
}
