package com.eignex.klause.cli

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The `dry-run-presolve` heap readout (issue #1415), whose numbers are only worth reporting if the
 *  seam actually reads the JVM's accounting rather than returning a placeholder. */
class HeapSampleTest {

    @Test
    fun `the jvm heap sample reports a live figure bounded by what is committed`() {
        val sample = assertNotNull(sampleHeap(), "the JVM target must report heap usage")

        assertTrue(sample.retainedBytes > 0, "a running JVM retains something")
        assertTrue(
            sample.committedBytes >= sample.retainedBytes,
            "committed ${sample.committedBytes} must cover retained ${sample.retainedBytes}",
        )
    }
}
