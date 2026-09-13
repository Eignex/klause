package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LpWorkTest {
    @Test
    fun `completed work saturates through both addition overloads`() {
        for (increment in listOf(0L, 1L, 2L, Long.MAX_VALUE)) {
            val work = LpWork()
            work.add(Long.MAX_VALUE - 1)

            work.add(increment)
            work.add(1)

            assertEquals(Long.MAX_VALUE, work.ops)
        }
    }

    @Test
    fun `reset starts ordinary accounting after saturation`() {
        val work = LpWork()
        work.add(Long.MAX_VALUE)
        work.add(10)

        work.reset()
        work.add(2)
        work.add(3L)

        assertEquals(5L, work.ops)
    }

    @Test
    fun `negative charges cannot replenish spent work`() {
        val work = LpWork()
        work.add(3)

        assertFailsWith<IllegalArgumentException> { work.add(-1) }
        assertFailsWith<IllegalArgumentException> { work.add(-1L) }

        assertEquals(3L, work.ops)
    }
}
