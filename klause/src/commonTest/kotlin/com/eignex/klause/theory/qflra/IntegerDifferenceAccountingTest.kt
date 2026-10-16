package com.eignex.klause.theory.qflra

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class IntegerDifferenceAccountingTest {
    @Test
    fun `accounting should forward each charge and poll without adding polls`() {
        val deadline = TimeSource.Monotonic.markNow()
        var polls = 0
        var spent = 0L
        val delegate = object : Cancellation {
            override fun isCancelled(): Boolean {
                polls++
                return spent >= 5L
            }
            override fun deadline() = deadline
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val observed = HashMap<String, Long>()
        val stop = IntegerDifferenceAccounting(delegate) { key, units -> observed[key] = units }

        stop.charge(2L)
        assertFalse(stop())
        checkNotNull(stop.workMeter()).charge(3L)

        assertEquals(1, polls)
        assertTrue(stop())
        assertEquals(2, polls)
        assertEquals(5L, spent)
        assertEquals(deadline, stop.deadline())
        stop.flush()
        assertEquals(5L, observed["INTEGER_DIFFERENCE_FORWARDED_CHARGE_UNITS"])
        assertEquals(5L, observed["INTEGER_DIFFERENCE_COMPLETED_CHARGE_UNITS"])
        assertEquals(2, polls)
    }

    @Test
    fun `a meterless wrapper should preserve the later composite meter`() {
        var spent = 0L
        val stop = IntegerDifferenceAccounting(Cancellation.Never) { _, _ -> }
        val metered = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }

        (stop or metered).charge(7L)

        assertNull(stop.workMeter())
        assertEquals(7L, spent)
    }

    @Test
    fun `an unsuccessful meter call should retain its forwarded request only`() {
        val delegate = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { error("refused") }
        }
        val observed = HashMap<String, Long>()
        val stop = IntegerDifferenceAccounting(delegate) { key, units -> observed[key] = units }

        assertFailsWith<IllegalStateException> { stop.charge(5L) }
        stop.flush()

        assertEquals(5L, observed["INTEGER_DIFFERENCE_FORWARDED_CHARGE_UNITS"])
        assertNull(observed["INTEGER_DIFFERENCE_COMPLETED_CHARGE_UNITS"])
    }
}
