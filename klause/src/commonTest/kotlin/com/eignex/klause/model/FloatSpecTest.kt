package com.eignex.klause.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class FloatSpecTest {

    @Test
    fun `scale and realValue decode buckets linearly`() {
        val s = FloatSpec(min = 0.0, max = 10.0, buckets = 11)
        assertEquals(1.0, s.scale)
        assertEquals(0.0, s.realValue(0))
        assertEquals(4.0, s.realValue(4))
        assertEquals(10.0, s.realValue(10))
    }

    @Test
    fun `scale handles a non-unit step`() {
        val s = FloatSpec(min = -1.0, max = 1.0, buckets = 5) // step (1-(-1))/4 = 0.5
        assertEquals(0.5, s.scale)
        assertEquals(-1.0, s.realValue(0))
        assertEquals(0.0, s.realValue(2))
        assertEquals(1.0, s.realValue(4))
    }

    @Test
    fun `requires at least two buckets`() {
        assertFails { FloatSpec(min = 0.0, max = 1.0, buckets = 1) }
    }
}
