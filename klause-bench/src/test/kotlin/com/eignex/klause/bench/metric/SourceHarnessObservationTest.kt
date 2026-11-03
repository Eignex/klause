package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceHarnessObservationTest {
    @Test
    fun `completed windows report harness counter deltas`() {
        val samples = listOf(
            SourceHarnessCounters(1_000_000, 2, 3, listOf("young"), threadId = 1),
            SourceHarnessCounters(9_000_000, 4, 13, listOf("young"), threadId = 1),
        ).iterator()
        val observation = SourceHarnessObservation { samples.next() }

        val stats = observation.finish()

        assertEquals(mapOf("sourceHarnessThreadCpuMs" to "8", "sourceHarnessGcCount" to "2",
            "sourceHarnessGcTimeMs" to "10"), stats)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "regressed", "failed"])
    fun `unavailable counters are omitted rather than reported as zero`(kind: String) {
        var reads = 0
        val observation = SourceHarnessObservation {
            when (kind) {
                "missing" -> SourceHarnessCounters()
                "failed" -> error("counter unavailable")
                else -> {
                    reads++
                    val value = if (reads == 1) 10L else 5L
                    SourceHarnessCounters(value, value, value, listOf("young"), threadId = 1)
                }
            }
        }

        val stats = observation.finish()

        assertTrue(stats.isEmpty())
    }

    @Test
    fun `changed thread ownership withholds calling thread CPU deltas`() {
        val samples = listOf(
            SourceHarnessCounters(threadCpuNanos = 1_000_000, threadId = 1),
            SourceHarnessCounters(threadCpuNanos = 9_000_000, threadId = 2),
        ).iterator()
        val observation = SourceHarnessObservation { samples.next() }

        val stats = observation.finish()

        assertTrue(stats.isEmpty())
    }

    @Test
    fun `changed collector ownership withholds GC deltas`() {
        val samples = listOf(
            SourceHarnessCounters(collectionCount = 2, collectionTimeMillis = 3, collectors = listOf("young")),
            SourceHarnessCounters(collectionCount = 4, collectionTimeMillis = 13, collectors = listOf("replacement")),
        ).iterator()
        val observation = SourceHarnessObservation { samples.next() }

        val stats = observation.finish()

        assertTrue(stats.isEmpty())
    }
}
