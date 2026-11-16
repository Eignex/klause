package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ArithmeticExecutionTest {
    private val pool = WorkCreditPool(100)
    private var stopped = false
    private val token: ExecutionControl = object : ExecutionControl() {
        override fun isCancelled(): Boolean = stopped || pool.exhausted
        override val admission: ArithmeticWorkSource get() = object : ArithmeticWorkSource {
            override val exhausted get() = pool.exhausted
            override fun reserve(maximum: Int): WorkReservation? = pool.reserve(maximum)
        }
    }
    private val execution = ArithmeticExecution(token) { error("cancelled") }

    @Test
    fun `settling partial credit should retain the containing checkpoint cadence`() {
        assertFailsWith<IllegalStateException> {
            execution.operation(8) {
                repeat(3) { execution.step() }
                execution.flush()
                stopped = true
                repeat(5) { execution.step() }
            }
        }

        assertEquals(8L, pool.spent)
    }

    @Test
    fun `weighted polling should share the admitted work cursor`() {
        assertFailsWith<IllegalStateException> {
            execution.operation(8) {
                repeat(2) { execution.step() }
                execution.pollingCost(4)
                stopped = true
                repeat(2) { execution.step() }
            }
        }

        assertEquals(4L, pool.spent)
    }

    @Test
    fun `bulk admission should preserve the containing work ceiling`() {
        assertFailsWith<IllegalStateException> {
            execution.operation(32) { execution.advance(101) {} }
        }

        assertEquals(100L, pool.spent)
    }

    @Test
    fun `copying an owner should retain its adapted admission allowance`() {
        val selected = WorkCreditPool(3)
        val adapted = object : ExecutionControl(token) {
            override val admission = object : ArithmeticWorkSource {
                override val exhausted get() = selected.exhausted
        override fun reserve(maximum: Int) = selected.reserve(maximum)
            }
        }
        val cursor = ArithmeticExecution(ExecutionControl(adapted)) { error("allowance") }

        assertFailsWith<IllegalStateException> {
            cursor.operation { repeat(4) { cursor.step() } }
        }

        assertEquals(3L, selected.spent)
        assertEquals(0L, pool.spent)
    }

    @Test
    fun `a callback should release credit and rebind its subsequent work owner`() {
        val second = WorkCreditPool(100)
        val replacement = object : ExecutionControl() {
            override fun isCancelled(): Boolean = second.exhausted
            override val admission = object : ArithmeticWorkSource {
                override val exhausted get() = second.exhausted
                override fun reserve(maximum: Int) = second.reserve(maximum)
            }
        }
        var current = token
        val control = object : ExecutionControl() {
            override fun isCancelled(): Boolean = current()
            override val admission get() = admissionFrom(current)
        }
        val cursor = ArithmeticExecution(control) { error("cancelled") }

        cursor.operation {
            cursor.step()
            cursor.handoff { current = replacement }
            cursor.step()
        }

        assertEquals(1L, pool.spent)
        assertEquals(1L, second.spent)
        assertEquals(99, assertNotNull(pool.reserve(100)).capacity)
    }

    @Test
    fun `replacing a stop predicate should retain the accounting owner allowance`(){
        val owned = ArithmeticExecution(ExecutionControl.Never.withAccounting(token)) { error("allowance") }

        assertFailsWith<IllegalStateException> {
            owned.operation { repeat(101) { owned.step() } }
        }

        assertEquals(100L, pool.spent)
    }

    @Test fun `checkpoint cost should observe a stop without spending additional work`() {
            assertFailsWith<IllegalStateException> {
            execution.operation(8){
                execution.step()
                stopped = true
                execution.pollingCost(8)
            }
        }

        assertEquals(1L, pool.spent)
        assertEquals(99, assertNotNull( pool.reserve(100)).capacity)
        }
    @Test
    fun `bulk admission should stop at the exact containing allowance`() {
        var admitted = 0

        assertFailsWith<IllegalStateException> {
            execution.operation { execution.advance(101) { admitted += it } }
        }

        assertEquals(100, admitted)
        assertEquals(100L, pool.spent)
        assertTrue(pool.exhausted) }

    @Test
    fun `pure checkpoints should spend no work`() {
        execution.checkpoint()
        execution.operation { execution.checkpoint() }

        assertEquals(0L, pool.spent)
    }

    @Test
    fun `an observed stop should remain latched until the active operation exits`() {
        assertFailsWith<IllegalStateException> {
            execution.operation {
                stopped = true
                assertFailsWith<IllegalStateException> { execution.checkpoint() }
                stopped = false
            }
        }

        assertEquals(0L, pool.spent)
    }

    @Test
    fun `an opaque checkpoint should permit a later caller invocation`() {
        var cancelled = true
        val opaque = ArithmeticExecution(ExecutionControl { cancelled }) { error("cancelled") }
        assertFailsWith<IllegalStateException> { opaque.checkpoint() }
        cancelled = false

        opaque.checkpoint()
    }

    @Test
    fun `nested operations should settle only admitted work at exit`() {
        execution.operation {
            repeat(3) { execution.step() }
            execution.operation { execution.step() }
        }

        assertEquals(4L, pool.spent)
        assertEquals(96, assertNotNull(pool.reserve(100)).capacity)
    }

    @Test
    fun `a looser nested quantum should retain the containing stop cadence`() {
        assertFailsWith<IllegalStateException> {
            execution.operation(4) {
                execution.operation(64) {
                    stopped = true
                    repeat(8) { execution.step() }
                }
            }
        }

        assertEquals(4L, pool.spent)
    }

    @Test
    fun `a smaller nested quantum should bound work admitted before a stop`() {
        assertFailsWith<IllegalStateException> {
            execution.operation(64) {
                repeat(5) { execution.step() }
                execution.operation(2) {
                    repeat(10) { index ->
                        if (index == 1) stopped = true
                        execution.step()
                    }
                }
            }
        }

        assertEquals(7L, pool.spent)
    }

    @Test
    fun `exceptional exits should release unused capacity and retain spent work`() {
        val failure = IllegalArgumentException("arithmetic failed")

        val caught = assertFailsWith<IllegalArgumentException> {
            execution.operation {
                execution.step()
                throw failure
            }
        }

        assertSame(failure, caught)
        assertEquals(1L, pool.spent)
        assertEquals(99, assertNotNull(pool.reserve(100)).capacity)
    }

    @Test
    fun `cleanup cancellation should preserve the primary arithmetic failure`() {
        val failure = IllegalArgumentException("arithmetic failed")

        val caught = assertFailsWith<IllegalArgumentException> {
            execution.operation {
                execution.step()
                stopped = true
                throw failure
            }
        }

        assertSame(failure, caught)
        assertEquals(1, caught.suppressedExceptions.size)
        assertEquals(1L, pool.spent)
    }

    @Test
    fun `containing exhaustion should prevent publication without overspending`() {
        var published = false

        assertFailsWith<IllegalStateException> {
            execution.operation(64) {
                repeat(101) { execution.step() }
                published = true
            }
        }

        assertFalse(published)
        assertEquals(100L, pool.spent)
        assertTrue(pool.exhausted)
    }

    @Test
    fun `cancellation at entry should admit no work`() {
        stopped = true

        assertFailsWith<IllegalStateException> { execution.operation { execution.step() } }

        assertEquals(0L, pool.spent)
    }

    @Test
    fun `a prepared operation should settle before every pause`() {
        val pending = execution.iterator(sequence {
            repeat(3) {
                execution.step()
                yield(it)
            }
        }.iterator())

        assertEquals(0, pending.next())
        assertEquals(1L, pool.spent)
        stopped = true

        assertFailsWith<IllegalStateException> { pending.next() }
        assertEquals(1L, pool.spent)
    }

    @Test
    fun `a live deadline should prevent publication inside a short final batch`() {
        val clock = TestTimeSource()
        val timed = ArithmeticExecution(ExecutionControl.until(clock.markNow() + 1.seconds)) { error("deadline") }

        assertFailsWith<IllegalStateException> {
            timed.operation {
                timed.step()
                clock += 1.seconds
            }
        }
    }

    @Test
    fun `opaque accounting should remain independent of operation checkpoints`() {
        val events = ArrayList<String>()
        val opaque = object : ExecutionControl() {
            override fun isCancelled(): Boolean { events += "poll"; return false }
            override val accounting: WorkMeter get() = WorkMeter { events += "charge:$it" }
        }
        val legacy = ArithmeticExecution(opaque) { error("cancelled") }

        legacy.operation {
            repeat(2) { legacy.step { events += "local" } }
        }

        assertEquals( listOf("poll", "local", "charge:1", "local", "charge:1", "poll"), events)
    }
}
