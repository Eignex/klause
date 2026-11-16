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
            "sourceHarnessGcTimeMs" to "10"), stats.filterKeys { it.startsWith("sourceHarness") })
    }

    @Test
    fun `phase counters exclude solver work from preparation and cleanup`() {
        val samples = listOf(
            SourceHarnessCounters(1_000_000, 1, 10, listOf("young"), threadId = 1),
            SourceHarnessCounters(3_000_000, 2, 20, listOf("young"), threadId = 1),
            SourceHarnessCounters(20_000_000, 6, 60, listOf("young"), threadId = 1),
            SourceHarnessCounters(25_000_000, 8, 80, listOf("young"), threadId = 1),
        ).iterator()
        val observation = SourceHarnessObservation { samples.next() }

        observation.preparationComplete()
        observation.cleanupStarted()
        val stats = observation.finish()

        assertEquals(mapOf("sourcePreparationThreadCpuMs" to "2", "sourcePreparationGcCount" to "1",
            "sourcePreparationGcTimeMs" to "10", "sourceCleanupThreadCpuMs" to "5",
            "sourceCleanupGcCount" to "2", "sourceCleanupGcTimeMs" to "20"),
            stats.filterKeys { !it.startsWith("sourceHarness") })
    }

    @Test
    fun `failed preparation ends its counters before cleanup`() {
        val samples = listOf(
            SourceHarnessCounters(threadCpuNanos = 1_000_000, threadId = 1),
            SourceHarnessCounters(threadCpuNanos = 4_000_000, threadId = 1),
            SourceHarnessCounters(threadCpuNanos = 9_000_000, threadId = 1),
        ).iterator()
        val observation = SourceHarnessObservation { samples.next() }

        observation.cleanupStarted()
        val stats = observation.finish()

        assertEquals("3", stats["sourcePreparationThreadCpuMs"])
        assertEquals("5", stats["sourceCleanupThreadCpuMs"])
    }

    @Test
    fun `failed phase sampling withholds that phase without losing whole counters`() {
        var reads = 0
        val observation = SourceHarnessObservation {
            reads++
            if (reads == 2) error("phase unavailable")
            SourceHarnessCounters(threadCpuNanos = reads * 1_000_000L, threadId = 1)
        }

        observation.preparationComplete()
        val stats = observation.finish()

        assertTrue(stats.keys.none { it.startsWith("sourcePreparation") })
        assertEquals("2", stats["sourceHarnessThreadCpuMs"])
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
