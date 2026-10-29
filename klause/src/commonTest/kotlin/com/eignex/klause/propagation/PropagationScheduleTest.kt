package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals

class PropagationScheduleTest {
    @Test
    fun `lower measured work should run before an expensive head`() {
        val schedule = PropagationSchedule()
        schedule.observe(FactorFamily.Scheduling, 100)
        schedule.observe(FactorFamily.Linear, 2)
        val queue = IntArrayDeque()
        queue.addLast(0)
        queue.addLast(1)

        val next = schedule.remove(queue) { if (it == 0) FactorFamily.Scheduling else FactorFamily.Linear }

        assertEquals(1, next)
        assertEquals(0, queue.removeFirst())
        assertEquals(3L, schedule.selectionWork)
    }

    @Test
    fun `a continuously requeued cheap factor should not starve the oldest expensive factor`() {
        val schedule = PropagationSchedule()
        schedule.observe(FactorFamily.Scheduling, 100)
        schedule.observe(FactorFamily.Linear, 1)
        val queue = IntArrayDeque()
        queue.addLast(0)
        queue.addLast(1)
        val order = ArrayList<Int>()

        repeat(9) {
            val next = schedule.remove(queue) { if (it == 0) FactorFamily.Scheduling else FactorFamily.Linear }
            order.add(next)
            queue.addLast(next)
        }

        assertEquals(List(8) { 1 } + 0, order)
    }

    @Test
    fun `unobserved families should receive a sampling opportunity`() {
        val schedule = PropagationSchedule()
        schedule.observe(FactorFamily.Linear, 100)
        val queue = IntArrayDeque()
        queue.addLast(0)
        queue.addLast(1)

        val next = schedule.remove(queue) { if (it == 0) FactorFamily.Linear else FactorFamily.Table }

        assertEquals(1, next)
    }

    @Test
    fun `an already cheap head should avoid inspecting the rest of the prefix`() {
        val schedule = PropagationSchedule()
        schedule.observe(FactorFamily.Linear, 2)
        val queue = IntArrayDeque()
        repeat(8) { queue.addLast(it) }
        var inspected = 0

        val next = schedule.remove(queue) {
            inspected++
            FactorFamily.Linear
        }

        assertEquals(0, next)
        assertEquals(1, inspected)
        assertEquals(1L, schedule.selectionWork)
    }

    @Test
    fun `observations should retain charged work and bypasses across queue replacement`() {
        val schedule = PropagationSchedule()
        schedule.observe(FactorFamily.Scheduling, 100)
        schedule.observe(FactorFamily.Linear, 2)
        val queue = IntArrayDeque()
        queue.addLast(0)
        queue.addLast(1)
        schedule.remove(queue) { if (it == 0) FactorFamily.Scheduling else FactorFamily.Linear }
        schedule.begin()

        schedule.remove(queue) { FactorFamily.Scheduling }

        assertEquals(2L, schedule.snapshot().getValue("propagationScheduleDispatches"))
        assertEquals(1L, schedule.snapshot().getValue("propagationScheduleBypasses"))
        assertEquals(0L, schedule.snapshot().getValue("propagationScheduleForced"))
        assertEquals(4L, schedule.snapshot().getValue("propagationScheduleWork"))
    }

    @Test
    fun `equal family costs should retain fifo order`() {
        val schedule = PropagationSchedule()
        val queue = IntArrayDeque()
        repeat(12) { queue.addLast(it) }

        val order = List(12) { schedule.remove(queue) { FactorFamily.Linear } }

        assertEquals((0 until 12).toList(), order)
    }

    @Test
    fun `selection should not inspect beyond its bounded prefix`() {
        val schedule = PropagationSchedule()
        schedule.observe(FactorFamily.Linear, 100)
        val queue = IntArrayDeque()
        repeat(9) { queue.addLast(it) }

        val next = schedule.remove(queue) { if (it == 8) FactorFamily.Table else FactorFamily.Linear }

        assertEquals(0, next)
        assertEquals(8L, schedule.selectionWork)
    }
}
