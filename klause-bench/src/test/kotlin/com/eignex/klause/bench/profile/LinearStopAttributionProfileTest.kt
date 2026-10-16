package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class LinearStopAttributionProfileTest {
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
