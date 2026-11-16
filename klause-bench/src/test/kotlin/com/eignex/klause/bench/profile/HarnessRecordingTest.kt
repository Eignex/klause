package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HarnessRecordingTest {
    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE])
    fun `representable durations preserve signed nanoseconds`(nanos: Long) {
        val duration = Duration.ofNanos(nanos)

        val result = HarnessRecording.durationNanos { duration }

        assertEquals(nanos, result)
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, Long.MAX_VALUE])
    fun `unrepresentable nanosecond durations are unavailable`(seconds: Long) {
        val duration = Duration.ofSeconds(seconds)

        val result = HarnessRecording.durationNanos { duration }

        assertNull(result)
    }

    @Test
    fun `overflow while reading a duration is unavailable`() {
        val result = HarnessRecording.durationNanos { throw ArithmeticException("long overflow") }

        assertNull(result)
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, 0, Long.MAX_VALUE])
    fun `representable timestamps preserve their instant text`(millis: Long) {
        val instant = Instant.ofEpochMilli(millis)

        val result = HarnessRecording.instantText { instant }

        assertEquals(instant.toString(), result)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unrepresentable timestamp reads are unavailable`(arithmeticOverflow: Boolean) {
        val result = HarnessRecording.instantText {
            if (arithmeticOverflow) throw ArithmeticException("long overflow")
            else throw DateTimeException("instant outside supported range")
        }

        assertNull(result)
    }
}
