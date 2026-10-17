package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompilationStudyCaptureStatusTest {
    @Test
    fun `successful child metadata leaves recording contents and source acceptance unproved`() {
        val record = Json.parseToJsonElement("""{"stats":{"exit":"0"}}""")

        val status = CompilationStudyCaptureStatus.inspect(record, true, 1, 1)

        assertTrue(status.recordedCliSucceeded)
        assertTrue(status.requestedRecordingFilesNonempty)
        assertFalse(status.recordingContentsVerified)
        assertFalse(status.sourceOutcomeValidated)
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"stats":{"exit":"137","killed":"hard-timeout"}}""",
        """{"stats":{"exit":"0","killed":"hard-timeout"}}""",
        """{"stats":{"exit":"0","unsupported":"model"}}""",
        """{"command":"ERROR","stats":{"exit":"0"}}""",
        """{"stats":{"exit":0}}""", """{"stats":{"exit":"0","other":true}}""",
        """{"stats":{}}""", """{"stats":[]} """, "{}", "null"])
    fun `failed unsupported or malformed child metadata cannot become a successful invocation`(text: String) {
        val record = Json.parseToJsonElement(text)

        val status = CompilationStudyCaptureStatus.inspect(record, true, 1, 1)

        assertFalse(status.recordedCliSucceeded)
    }

    @Test
    fun `force killed child retains its original exit and termination`() {
        val record = Json.parseToJsonElement("""{"stats":{"exit":"137","killed":"hard-timeout"}}""")

        val status = CompilationStudyCaptureStatus.inspect(record, true, 0, 1)

        assertEquals("137", status.recordedCliExit)
        assertEquals("hard-timeout", status.recordedCliTermination)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `a missing or empty requested recording remains incomplete`(bytes: Long) {
        val record = Json.parseToJsonElement("""{"stats":{"exit":"0"}}""")

        val status = CompilationStudyCaptureStatus.inspect(record, true, bytes, 1)

        assertFalse(status.requestedJfrNonempty!!)
        assertFalse(status.requestedRecordingFilesNonempty)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `a missing or empty requested compilation log remains incomplete`(bytes: Long) {
        val record = Json.parseToJsonElement("""{"stats":{"exit":"0"}}""")

        val status = CompilationStudyCaptureStatus.inspect(record, true, 1, bytes)

        assertFalse(status.requestedCompilationLogNonempty!!)
        assertFalse(status.requestedRecordingFilesNonempty)
    }

    @Test
    fun `unrequested recordings remain absent in an unprofiled invocation`() {
        val record = Json.parseToJsonElement("""{"stats":{"exit":"0"}}""")

        val status = CompilationStudyCaptureStatus.inspect(record, false, 0, 0)

        assertNull(status.requestedJfrNonempty)
        assertNull(status.requestedCompilationLogNonempty)
        assertTrue(status.requestedRecordingFilesNonempty)
    }
}
