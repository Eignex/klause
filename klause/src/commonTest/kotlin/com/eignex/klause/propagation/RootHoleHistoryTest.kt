package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootHoleHistoryTest {
    @Test
    fun `root hole membership preserves gaps across insertion orders`() {
        for (values in listOf(
            longArrayOf(-2, -1, 0, 2, 3),
            longArrayOf(3, 2, 0, -1, -2),
            longArrayOf(-2, 0, -1, 3, 2, -1, 2),
        )) {
            val history = RootHoleHistory()
            values.forEach(history::add)

            for (value in -3L..4L) {
                assertEquals(value in values, value in history, "value $value")
            }
        }
    }

    @Test
    fun `extreme root holes keep separate neighborhoods`() {
        val history = RootHoleHistory()
        for (value in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 0L)) {
            history.add(value)
        }

        assertTrue(Long.MIN_VALUE in history)
        assertTrue(Long.MIN_VALUE + 1L in history)
        assertFalse(Long.MIN_VALUE + 2L in history)
        assertTrue(0L in history)
        assertFalse(Long.MAX_VALUE - 2L in history)
        assertTrue(Long.MAX_VALUE - 1L in history)
        assertTrue(Long.MAX_VALUE in history)
    }
}
