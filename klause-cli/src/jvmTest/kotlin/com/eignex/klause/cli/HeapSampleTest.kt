package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeapSampleTest {

    private val transientBytes = 16 * 1024 * 1024

    @Test
    fun `the jvm heap sample reports a live figure bounded by what is committed`() {
        val sample = assertNotNull(sampleHeap(), "the JVM target must report heap usage")

        assertTrue(sample.retainedBytes > 0, "a running JVM retains something")
        assertTrue(
            sample.committedBytes >= sample.retainedBytes,
            "committed ${sample.committedBytes} must cover retained ${sample.retainedBytes}",
        )
    }

    @Test
    fun `the peak heap keeps a transient that the retained figure drops`() {
        startHeapPeakSampler()
        var block: ByteArray? = ByteArray(transientBytes)
        block?.set(transientBytes - 1, 1)
        val holding = assertNotNull(assertNotNull(sampleHeap()).peakBytes)
        block = null

        val after = assertNotNull(sampleHeap())
        val peak = assertNotNull(after.peakBytes, "the peak is reported once the sampler is started")

        assertTrue(peak >= holding, "a high-water mark cannot fall")
        assertTrue(
            peak - after.retainedBytes >= transientBytes / 2,
            "peak $peak must stay above retained ${after.retainedBytes} once the transient is dropped",
        )
    }
}
