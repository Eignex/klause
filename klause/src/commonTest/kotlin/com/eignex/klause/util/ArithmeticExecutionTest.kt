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
    private val token: ArithmeticCancellation = object : ArithmeticCancellation {
        override fun isCancelled(): Boolean = stopped || pool.exhausted
        override fun arithmeticSource(): ArithmeticWorkSource = object : ArithmeticWorkSource {
            override fun isCancelled(): Boolean = token.isCancelled()
            override fun reserve(maximum: Int): WorkReservation? = pool.reserve(maximum)
        }
    }
    private val execution = ArithmeticExecution(token) { error("cancelled") }

    @Test
    fun `pure checkpoints should spend no work`() {
        execution.checkpoint()
        execution.operation { execution.checkpoint() }

        assertEquals(0L, pool.spent)
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
        val timed = ArithmeticExecution(Cancellation.until(clock.markNow() + 1.seconds)) { error("deadline") }

        assertFailsWith<IllegalStateException> {
            timed.operation {
                timed.step()
                clock += 1.seconds
            }
        }
    }

    @Test
    fun `an opaque meter should retain per debit predicate order`() {
        val events = ArrayList<String>()
        val opaque = object : Cancellation {
            override fun isCancelled(): Boolean { events += "poll"; return false }
            override fun workMeter(): WorkMeter = WorkMeter { events += "charge:$it" }
        }
        val legacy = ArithmeticExecution(opaque) { error("cancelled") }

        legacy.operation {
            repeat(2) { legacy.step { events += "local" } }
        }

        assertEquals(List(2) { listOf("poll", "local", "charge:1", "poll") }.flatten(), events)
    }
}
