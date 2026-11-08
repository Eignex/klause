package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeCapQueueTest {
    @Test
    fun `interleaved queues should recycle pages without changing FIFO order`() {
        val pool = NativeCapPages()
        repeat(3) { pool.reserve() }
        val first = NativeCapQueue()
        val second = NativeCapQueue()
        repeat(16) { first.push(it, pool) }
        repeat(8) { second.push(100 + it, pool) }
        assertFalse(second.canPush(pool))

        repeat(8) { assertEquals(it, first.pop(pool)) }
        repeat(8) { second.push(108 + it, pool) }

        repeat(8) { assertEquals(8 + it, first.pop(pool)) }
        repeat(16) { assertEquals(100 + it, second.pop(pool)) }
        assertEquals(0, first.size)
        assertEquals(0, second.size)
        assertEquals(3L, pool.pages)
        assertEquals(3L, pool.available)
        repeat(3) { assertTrue(pool.release()) }
        assertEquals(0L, pool.slots)
        assertFalse(pool.release())
    }
}
