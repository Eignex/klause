package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformJvmTest {

    private val transientBytes = 16 * 1024 * 1024

    @Test
    fun `elapsed clock milliseconds should discard a fractional remainder`() {
        assertEquals(1L, elapsedMonotonicMillis(17L, 1_000_026L))
    }

    @Test
    fun `a short clock interval should survive a wrapped nano reading`() {
        assertEquals(1L, elapsedMonotonicMillis(Long.MAX_VALUE - 500_000L, Long.MIN_VALUE + 499_999L))
    }

    @Test
    fun `a negative clock interval should truncate toward zero`() {
        assertEquals(0L, elapsedMonotonicMillis(1_000_000L, 1L))
    }

    @Test
    fun `the jvm heap sample should report a live figure bounded by what is committed`() {
        val sample = assertNotNull(sampleHeap(), "the JVM target must report heap usage")

        assertTrue(sample.retainedBytes > 0)
        assertTrue(
            sample.committedBytes >= sample.retainedBytes,
            "committed ${sample.committedBytes} must cover retained ${sample.retainedBytes}",
        )
    }

    @Test
    fun `the peak heap should keep a transient that the retained figure drops`() {
        startHeapPeakSampler()
        var block: ByteArray? = ByteArray(transientBytes)
        block?.set(transientBytes - 1, 1)
        val holding = assertNotNull(assertNotNull(sampleHeap()).peakBytes)
        block = null

        val after = assertNotNull(sampleHeap())
        val peak = assertNotNull(after.peakBytes, "the peak is reported once the sampler is started")

        assertTrue(peak >= holding)
        assertTrue(
            peak - after.retainedBytes >= transientBytes / 2,
            "peak $peak must stay above retained ${after.retainedBytes} once the transient is dropped",
        )
    }
}
