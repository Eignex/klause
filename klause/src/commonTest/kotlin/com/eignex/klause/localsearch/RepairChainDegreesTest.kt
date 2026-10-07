package com.eignex.klause.localsearch

import kotlin.test.Test
import kotlin.test.assertEquals

class RepairChainDegreesTest {
    @Test
    fun `regression is measured against the first recorded degree`() {
        val book = RepairChainDegrees()
        book.record(0, 2)
        book.record(0, 5)
        book.record(1, 0)

        val target = book.worstRegressed(intArrayOf(5, 1), doubleArrayOf(1.0, 2.0))

        assertEquals(0, target)
    }

    @Test
    fun `a narrow walk after growth retains its tied target and forgets old factors`() {
        val book = RepairChainDegrees()
        val degrees = IntArray(80) { 1 }
        val weights = DoubleArray(80) { 1.0 }
        book.record(1, 0)
        book.record(2, 0)
        val target = book.worstRegressed(degrees, weights)
        book.clear()
        for (fid in degrees.indices) book.record(fid, 0)
        book.clear()
        book.record(1, 0)
        book.record(2, 0)
        degrees[0] = 100

        assertEquals(target, book.worstRegressed(degrees, weights))
        book.clear()
        assertEquals(-1, book.worstRegressed(degrees, weights))
    }
}
