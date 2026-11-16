package com.eignex.klause.util

import com.eignex.klause.presolve.PresolveBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanMeterTest {
    @Test
    fun `nested scans should share their containing checkpoint cadence`() {
        var polls = 0
        val control = ExecutionControl {
            polls++
            false
        }.bindStopControl()

        repeat(256) {
            ScanMeter(control).use { scan ->
                assertTrue(scan.visit(1L))
                assertTrue(scan.check())
            }
        }

        assertEquals(1, polls)
    }

    @Test
    fun `scan prices should not multiply cancellation polls`() {
        var polls = 0
        val scan = ScanMeter(
            ExecutionControl {
                polls++
                false
            },
        )

        repeat(1024) { assertTrue(scan.visit(5000L)) }
        assertEquals(1, polls)
        assertTrue(scan.visit(1L))

        assertEquals(2, polls)
    }

    @Test
    fun `a scan should settle all completed visits at its boundary`() {
        var work = 0L
        val scan = ScanMeter(ExecutionControl(Cancellation.Never, WorkMeter { work += it }))

        repeat(10) { assertTrue(scan.visit(5000L)) }
        scan.flush()

        assertEquals(50000L, work)
    }

    @Test
    fun `pending visits should observe a quota independently of the stop predicate`() {
        val budget = PresolveBudget(10L)
        val control = budget.slice(10L) and ExecutionControl { false }
        val scan = ScanMeter(control)
        repeat(3) { assertTrue(scan.visit(3L)) }

        assertFalse(scan.visit(1L))

        assertEquals(10L, budget.spent())
    }
}
