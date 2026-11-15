package com.eignex.klause.presolve

import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.ArithmeticExecution
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class PresolveBudgetTest {
    @Test
    fun `nested budget stops should observe charges made by preceding predicates`() {
        val budget = PresolveBudget(100)
        val visited = ArrayList<Int>()
        val first = ExecutionControl {
            visited += 1
            budget.charge(10)
            false
        }
        val last = ExecutionControl {
            visited += 2
            false
        }
        val stop = budget.orSpent(first or budget.slice(5) or last)

        assertTrue(stop())

        assertEquals(listOf(1), visited)
        assertEquals(10L, budget.spent())
    }

    @Test
    fun `independent budget stops should not narrow the selected admission owner`() {
        val budget = PresolveBudget(100)
        val other = PresolveBudget(100)
        val source = assertNotNull((budget.slice(80) or other.slice(10)).admission)

        val granted = assertNotNull(source.reserve(100))
        granted.settle(20)

        assertEquals(80, granted.capacity)
        assertEquals(20L, budget.spent())
        assertEquals(0L, other.spent())
    }

    @Test
    fun `boolean conjunction should not mask exhaustion of a containing slice`() {
        val budget = PresolveBudget(100)
        val control = (budget.slice(80) or budget.slice(10)) and ExecutionControl { false }
        val execution = ArithmeticExecution(control) { error("allowance") }

        assertFailsWith<IllegalStateException> {
            execution.operation { repeat(10) { execution.step() } }
        }

        assertEquals(10L, budget.spent())
    }

    @Test
    fun `held slice credit should not establish permanent exhaustion`() {
        val budget = PresolveBudget(100)
        val source = assertNotNull((budget.slice(80) or budget.slice(10)).admission)
        val granted = assertNotNull(source.reserve(100))

        assertNull(source.reserve(1))

        assertFalse(source.exhausted)
        granted.settle(0)
        assertEquals(10, assertNotNull(source.reserve(100)).capacity)
    }

    @Test
    fun `a composed slice should admit only its tightest remaining credit`() {
        val budget = PresolveBudget(100)
        val containing = budget.slice(80)
        val limited = containing or budget.slice(10)
        val source = assertNotNull(limited.admission)

        val credit = assertNotNull(source.reserve(100))
        credit.settle(credit.capacity)

        assertEquals(10, credit.capacity)
        assertEquals(10L, budget.spent())
        assertSame(containing.accounting, limited.accounting)
        assertFalse(containing())
        assertTrue(limited())
    }

    @Test
    fun `slice admission should return unused credit without renewing its allowance`() {
        val budget = PresolveBudget(11)
        val source = assertNotNull(budget.slice(7).admission)
        val first = assertNotNull(source.reserve(10))
        assertEquals(7, first.capacity)
        assertNull(source.reserve(1))

        first.settle(3)
        val remaining = assertNotNull(source.reserve(10))
        remaining.settle(remaining.capacity)

        assertEquals(4, remaining.capacity)
        assertEquals(7L, budget.spent())
        assertTrue(source.exhausted)
    }

    @Test
    fun `direct stop observation should reuse only its owning budget token`() {
        val budget = PresolveBudget(100)
        val other = PresolveBudget(100)
        val token = budget.observeStops(ExecutionControl { true })
        val first = PresolveInvocation("first", PresolveLane.SOURCE, PresolveSizeStats(0, 0, 0))
        val second = PresolveInvocation("second", PresolveLane.SOURCE, PresolveSizeStats(0, 0, 0))
        budget.activeInvocation = first
        assertTrue(budget.observeStops(token)())
        assertTrue(first.interrupted)
        first.interrupted = false
        budget.activeInvocation = second
        other.activeInvocation = first

        assertSame(token, budget.observeStops(token))
        assertTrue(other.observeStops(token)())
        assertTrue(first.interrupted)
        assertTrue(second.interrupted)
    }

    @Test
    fun `stop observation should retain newer predicates with the same meter`() {
        val budget = PresolveBudget(100)
        var stopped = false
        val token = budget.slice(50) or ExecutionControl { stopped }
        val observed = budget.observeStops(token)

        assertFalse(observed())
        stopped = true

        assertTrue(observed())
        assertEquals(100L, budget.remaining())
    }

    @Test
    fun `stop observation should retain a newer slice allowance`() {
        val budget = PresolveBudget(100)
        val original = budget.slice(80)
        val observed = budget.observeStops(original or budget.slice(10))

        observed.charge(10)

        assertFalse(original())
        assertTrue(observed())
        assertEquals(90L, budget.remaining())
    }

    @Test
    fun `stop observation should forward the deadline meter and ordered predicates`() {
        val budget = PresolveBudget(100)
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        var work = 0L
        var laterCalls = 0
        val meter = WorkMeter { work += it }
        val token = object : ExecutionControl() {
            override fun isCancelled(): Boolean = true
            override fun deadline() = deadline
            override val accounting get() = meter
        } or ExecutionControl {
            laterCalls++
            false
        }
        val observed = budget.observeStops(budget.observeStops(token))

        observed.charge(7L)

        assertTrue(observed())
        assertEquals(0, laterCalls)
        assertEquals(deadline, observed.deadline())
        assertSame(meter, observed.accounting)
        assertEquals(7L, work)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `a slice should fire once its share of the budget is charged`() {
        val budget = PresolveBudget(1000)
        val slice = budget.slice(400)

        assertFalse(slice())
        budget.charge(300)
        assertFalse(slice(), "300 of the 400 share charged")
        budget.charge(100)
        assertTrue(slice())
    }

    @Test
    fun `a slice should fire when the whole budget runs out before its share does`() {
        val budget = PresolveBudget(300)
        val slice = budget.slice(1000)

        assertFalse(slice())
        budget.charge(300)
        assertTrue(slice())
    }

    @Test
    fun `an unspent share should be left in the pool for the next slice`() {
        val budget = PresolveBudget(1000)
        val first = budget.slice(500)
        budget.charge(100)
        assertFalse(first())

        val second = budget.slice(budget.remaining())
        budget.charge(800)
        assertFalse(second(), "the second slice was taken from the full 900 that was left")
        budget.charge(100)
        assertTrue(second())
    }

    @Test
    fun `work charged through a composed slice should land in the budget`() {
        val budget = PresolveBudget(1000)
        val token = budget.slice(500) or ExecutionControl.Never

        token.charge(200)

        assertEquals(800L, budget.remaining())
    }

    @Test
    fun `the phase token should fire once its budget is spent`() {
        val budget = PresolveBudget(100)
        val phase = budget.orSpent(ExecutionControl.Never)
        assertSame(budget.slice(100).accounting, phase.accounting)

        phase.charge(100)

        assertTrue(phase())
    }

    @Test
    fun `the phase token should fire when the outer token does`() {
        assertTrue(PresolveBudget(100).orSpent(ExecutionControl { true })())
    }
}
