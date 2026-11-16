package com.eignex.klause.util

import com.eignex.kumulant.core.Concurrency
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkCreditPoolConcurrencyTest {
    @Test
    fun `concurrent reservations should never admit more than the shared allowance`() {
        val pool = WorkCreditPool(10_000, Concurrency.Relaxed)
        val used = LongArray(4)
        val workers = used.indices.map { index ->
            thread {
                while (true) {
                    val credit = pool.reserve(17) ?: break
                    used[index] += credit.capacity
                    credit.settle(credit.capacity)
                }
            }
        }

        workers.forEach { it.join() }

        assertEquals(10_000L, used.sum())
        assertEquals(used.sum(), pool.spent)
        assertTrue(pool.exhausted)
    }
}
