package com.eignex.klause.util

import com.eignex.kumulant.core.Concurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkCreditPoolTest {
    @Test
    fun `reserved capacity should not count as spent or exhausted`() {
        for (mode in listOf(Concurrency.None, Concurrency.Relaxed)) {
            val pool = WorkCreditPool(5, mode)
            val grant = assertNotNull(pool.reserve(5))

            assertNull(pool.reserve(1))
            assertFalse(pool.exhausted)
            assertEquals(0L, pool.spent)
            grant.settle(2)

            assertEquals(2L, pool.spent)
            assertEquals(3, assertNotNull(pool.reserve(5)).capacity)
        }
    }

    @Test
    fun `settled work should not be charged twice`() {
        val pool = WorkCreditPool(2)
        val grant = assertNotNull(pool.reserve(2))
        grant.settle(2)

        assertFailsWith<IllegalStateException> { grant.settle(2) }

        assertEquals(2L, pool.spent)
        assertTrue(pool.exhausted)
    }

    @Test
    fun `temporarily unavailable containing credit should release child reservations`() {
        val child = WorkCreditPool(8)
        val parent = WorkCreditPool(8)
        val held = assertNotNull(parent.reserve(8))
        val containing = object : ArithmeticWorkSource {
            override fun isCancelled(): Boolean = parent.exhausted
            override fun reserve(maximum: Int): WorkReservation? = parent.reserve(maximum)
        }

        assertNull(reserveWork(8, listOf(child), containing) {})

        assertEquals(8, assertNotNull(child.reserve(8)).capacity)
        assertFalse(containing.isCancelled())
        held.settle(0)
        assertEquals(8, assertNotNull(parent.reserve(8)).capacity)
    }

    @Test
    fun `a failed containing reservation should return child credit`() {
        val child = WorkCreditPool(8)
        val containing = object : ArithmeticWorkSource {
            override fun isCancelled(): Boolean = false
            override fun reserve(maximum: Int): WorkReservation = error("reservation failed")
        }

        assertFailsWith<IllegalStateException> { reserveWork(8, listOf(child), containing) {} }

        assertEquals(8, assertNotNull(child.reserve(8)).capacity)
        assertEquals(0L, child.spent)
    }

    @Test
    fun `containing work should settle even when a diagnostic callback fails`() {
        val child = WorkCreditPool(8)
        val parent = WorkCreditPool(8)
        val containing = object : ArithmeticWorkSource {
            override fun isCancelled(): Boolean = parent.exhausted
            override fun reserve(maximum: Int): WorkReservation? = parent.reserve(maximum)
        }
        val grant = assertNotNull(reserveWork(8, listOf(child), containing) { error("diagnostic failed") })

        assertFailsWith<IllegalStateException> { grant.settle(3) }

        assertEquals(3L, child.spent)
        assertEquals(3L, parent.spent)
        assertEquals(5, assertNotNull(parent.reserve(8)).capacity)
    }

    @Test
    fun `direct and reserved debits should saturate exactly`() {
        for (mode in listOf(Concurrency.None, Concurrency.Relaxed)) {
            val pool = WorkCreditPool(null, mode)
            val grant = assertNotNull(pool.reserve(8))
            pool.debit(Long.MAX_VALUE - 1)

            grant.settle(8)
            pool.debit(1)

            assertEquals(Long.MAX_VALUE, pool.spent)
        }
    }
}
