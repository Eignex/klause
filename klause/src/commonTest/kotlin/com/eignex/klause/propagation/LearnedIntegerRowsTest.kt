package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearnedIntegerRowsTest {
    @Test
    fun `large cache admissions should fit the existing work grant`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        val rows = Array(20_000) { LearnedIntegerPropagator(row) }
        val cache = LearnedIntegerRows()
        var maximumWork = 0L

        for (propagator in rows) {
            maximumWork = maxOf(maximumWork, cache.admissionWork)
            cache.admit(propagator)
        }

        assertTrue(maximumWork <= 32_768L)
        assertEquals(rows.size, cache.size)
        for (index in listOf(0, 63, 64, 127, 128, rows.lastIndex)) {
            assertSame(rows[index], cache[index])
            assertEquals(index, rows[index].retentionSlot)
        }
    }

    @Test
    fun `retired tail capacity should release within the small surviving row grant`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        val rows = Array(20_000) { LearnedIntegerPropagator(row) }
        val cache = LearnedIntegerRows()
        for (propagator in rows) cache.admit(propagator)
        for (index in 1 until rows.size) cache.unlink(rows[index])
        val budget = ReclamationPreparation(32_768L, 4096L, { false }, {})

        budget.visit(cache.trimmingWork, cache.trimmingScratchBytes)
        budget.visit(0L)
        cache.trim()

        assertEquals(7L, budget.work)
        assertEquals(1, cache.capacity)
        assertEquals(3L, cache.referenceCapacity)
        assertSame(rows[0], cache[0])
        assertEquals(0, rows[0].retentionSlot)
    }

    @Test
    fun `a large surviving prefix should trim within the existing scratch grant`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        val rows = Array(20_000) { LearnedIntegerPropagator(row) }
        val cache = LearnedIntegerRows()
        for (propagator in rows) cache.admit(propagator)
        for (index in rows.lastIndex downTo 10_001) cache.unlink(rows[index])
        val budget = ReclamationPreparation(32_768L, 4096L, { false }, {})

        budget.visit(cache.trimmingWork, cache.trimmingScratchBytes)
        budget.visit(0L)
        cache.trim()

        assertEquals(10_001, cache.capacity)
        assertEquals(10_001, cache.size)
        assertTrue(budget.work <= 32_768L)
        assertSame(rows[10_000], cache[10_000])
    }

    @Test
    fun `trimmed partial pages should preserve swap removal after another admission`() {
        val row = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0),
            longArrayOf(1), intArrayOf(0), LinearOp.LE, 10), useLo = true))
        val rows = Array(130) { LearnedIntegerPropagator(row) }
        val cache = LearnedIntegerRows()
        for (propagator in rows) cache.admit(propagator)
        cache.unlink(rows[129])
        cache.trim()
        val appended = LearnedIntegerPropagator(row)
        cache.admit(appended)

        cache.unlink(rows[64])

        assertEquals(129, cache.size)
        assertSame(appended, cache[64])
        assertEquals(64, appended.retentionSlot)
        assertEquals(-1, rows[64].retentionSlot)
    }
}
