package com.eignex.klause.presolve

import com.eignex.klause.util.ArithmeticExecution
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.bindStopControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresolveScanTest {
    @Test
    fun `nested scalar scans should share the phase checkpoint cadence`() {
        var polls = 0
        val control = ExecutionControl {
            polls++
            false
        }.bindStopControl()

        repeat(256) {
            PresolveScan(control).use { scan -> assertFalse(scan.poll()) }
        }

        assertEquals(1, polls)
    }

    @Test
    fun `a bound scalar scan should observe its exhausted slice immediately`() {
        val budget = PresolveBudget(100)
        val scan = PresolveScan(budget.slice(10).bindStopControl())
        assertFalse(scan.poll())

        scan.charge(10)

        assertTrue(scan.poll())
    }

    @Test
    fun `scalar polling should stop at the selected slice without waiting for another external poll`() {
        val budget = PresolveBudget(100)
        val scan = PresolveScan(budget.slice(10))
        assertFalse(scan.poll())

        scan.charge(10)

        assertTrue(scan.poll())
        assertEquals(10L, budget.spent())
    }

    @Test
    fun `an arithmetic checkpoint should bypass the scalar polling countdown`() {
        val budget = PresolveBudget(100)
        var stopped = false
        val scan = PresolveScan(budget.slice(100) or ExecutionControl { stopped })
        val execution = ArithmeticExecution(scan) { error("cancelled") }

        assertFailsWith<IllegalStateException> {
            execution.operation(16) {
                execution.step()
                stopped = true
                repeat(16) { execution.step() }
            }
        }

        assertEquals(16L, budget.spent())
    }

    @Test
    fun `closing a scalar scan should force observation of a newly requested stop`() {
        var stopped = false
        val scan = PresolveScan(ExecutionControl { stopped })
        assertFalse(scan.poll())
        stopped = true

        scan.close()

        assertTrue(scan.poll())
    }
}
