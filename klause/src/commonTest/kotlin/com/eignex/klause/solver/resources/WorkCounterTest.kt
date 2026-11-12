package com.eignex.klause.solver.resources

import com.eignex.kumulant.core.Concurrency
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkCounterTest {
    @Test
    fun `unit charges should remain exact above floating point integer precision`() {
        for (concurrency in listOf(Concurrency.None, Concurrency.Relaxed)) {
            val initial = 1L shl 53
            val counter = WorkCounter(concurrency, initial)

            counter.add(1L)

            assertEquals(initial + 1L, counter.value)
        }
    }

    @Test
    fun `overflow should saturate without restoring capacity`() {
        for (concurrency in listOf(Concurrency.None, Concurrency.Relaxed)) {
            val counter = WorkCounter(concurrency, Long.MAX_VALUE - 1L)

            counter.add(2L)
            counter.add(1L)

            assertEquals(Long.MAX_VALUE, counter.value)
        }
    }

    @Test
    fun `a zero observation should initialize an absent diagnostic`() {
        for (concurrency in listOf(Concurrency.None, Concurrency.Relaxed)) {
            val counter = WorkCounter(concurrency, -1L)

            counter.add(0L)

            assertEquals(0L, counter.value)
        }
    }
}
