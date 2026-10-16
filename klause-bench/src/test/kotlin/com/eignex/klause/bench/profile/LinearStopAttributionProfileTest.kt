package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class LinearStopAttributionProfileTest {
    @Test
    fun `request should retain the default containing timeout`() {
        val timeout = LinearStopAttributionProfile.requestedTimeoutMs(arrayOf("request64="))

        assertEquals(60000L, timeout)
    }

    @ParameterizedTest
    @ValueSource(longs = [1L, 10000L, 60000L])
    fun `request should preserve an explicit containing timeout`(requested: Long) {
        val timeout = LinearStopAttributionProfile.requestedTimeoutMs(
            arrayOf("request64=", "timeoutMs=$requested"),
        )

        assertEquals(requested, timeout)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "0", "-1", "60001", "1.5", "invalid", "9223372036854775808"])
    fun `request should reject invalid containing timeouts`(requested: String) {
        assertFailsWith<IllegalArgumentException> {
            LinearStopAttributionProfile.requestedTimeoutMs(arrayOf("request64=", "timeoutMs=$requested"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["timeout=10000", "processors=1"])
    fun `request should reject unrecognized timeout arguments`(argument: String) {
        assertFailsWith<IllegalArgumentException> {
            LinearStopAttributionProfile.requestedTimeoutMs(arrayOf("request64=", argument))
        }
    }

    @Test
    fun `request should reject repeated timeout arguments`() {
        assertFailsWith<IllegalArgumentException> {
            LinearStopAttributionProfile.requestedTimeoutMs(
                arrayOf("request64=", "timeoutMs=10000", "timeoutMs=10000"),
            )
        }
    }

    @Test
    fun `observation should retain the first stopped poll without adding polls`() {
        var polls = 0
        val stop = LinearObservedStop(Cancellation { ++polls >= 2 }, true)

        assertEquals(false, stop())
        assertNull(stop.observation)
        assertEquals(true, stop())
        val first = assertNotNull(stop.observation)
        assertEquals(true, stop())

        assertEquals(3, polls)
        assertSame(first, stop.observation)
    }

    @Test
    fun `plain observation should forward metadata and charges without capturing a stack`() {
        var spent = 0L
        val meter = WorkMeter { spent += it }
        val deadline = Cancellation.after(1.seconds).deadline()
        val delegate = object : Cancellation {
            override fun isCancelled() = true
            override fun deadline() = deadline
            override fun workMeter() = meter
        }
        val stop = LinearObservedStop(delegate, false)

        stop.charge(7)

        assertEquals(true, stop())
        assertSame(deadline, stop.deadline())
        assertSame(meter, stop.workMeter())
        assertEquals(7L, spent)
        assertNull(stop.observation)
    }
}
