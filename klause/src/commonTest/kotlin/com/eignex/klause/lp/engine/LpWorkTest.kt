package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LpWorkTest {
    @Test
    fun `bounded charges decline without changing retained work`() {
        for (limit in listOf(1L, 5L, Long.MAX_VALUE)) {
            val work = LpWork()
            work.reset(limit)
            work.add(limit - 1L)

            assertFailsWith<LpWorkExhausted> { work.add(2) }
            assertFailsWith<LpWorkExhausted> { work.add(2L) }

            assertEquals(limit - 1L, work.ops)
        }
    }

    @Test
    fun `exactly funded charges consume the complete grant`() {
        val work = LpWork()
        work.reset(5L)

        work.add(2)
        work.add(3L)
        work.add(0L)

        assertEquals(5L, work.ops)
        assertFailsWith<LpWorkExhausted> { work.add(1L) }
    }

    @Test
    fun `reset replaces a bounded invocation grant`() {
        val work = LpWork()
        work.reset(5L)
        work.add(4L)

        work.reset(3L)
        work.add(3L)

        assertEquals(3L, work.ops)
        assertFailsWith<LpWorkExhausted> { work.add(1L) }
    }

    @Test
    fun `completed work should saturate through both addition overloads`() {
        for (increment in listOf(0L, 1L, 2L, Long.MAX_VALUE)) {
            val work = LpWork()
            work.add(Long.MAX_VALUE - 1)

            work.add(increment)
            work.add(1)

            assertEquals(Long.MAX_VALUE, work.ops)
        }
    }

    @Test
    fun `reset should start ordinary accounting after saturation`() {
        val work = LpWork()
        work.add(Long.MAX_VALUE)
        work.add(10)

        work.reset()
        work.add(2)
        work.add(3L)

        assertEquals(5L, work.ops)
    }

    @Test
    fun `negative charges should not replenish spent work`() {
        val work = LpWork()
        work.add(3)

        assertFailsWith<IllegalArgumentException> { work.add(-1) }
        assertFailsWith<IllegalArgumentException> { work.add(-1L) }

        assertEquals(3L, work.ops)
    }
}
