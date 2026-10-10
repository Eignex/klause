package com.eignex.klause.solver

import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentTest {
    @Test
    fun `copied mixed values remain independent of the source`() {
        val source = Assignment(65, 1, 1)
        source.setBool(0, true)
        source.setBool(64, true)
        source.setInt(0, Long.MIN_VALUE)
        source.setReal(0, -0.0)
        val target = Assignment(65, 1, 1)

        source.copyInto(target)
        source.flipBool(0)
        source.flipBool(64)
        source.setInt(0, Long.MAX_VALUE)
        source.setReal(0, 1.5)

        assertEquals(
            Sample(BooleanArray(65) { it == 0 || it == 64 }, longArrayOf(Long.MIN_VALUE), doubleArrayOf(-0.0)),
            target.snapshot(),
        )
    }

    @Test
    fun `reusing copied storage preserves an earlier published sample`() {
        val source = Assignment(65, 1, 1)
        source.setBool(64, true)
        source.setInt(0, 3)
        source.setReal(0, 0.5)
        val target = Assignment(65, 1, 1)
        source.copyInto(target)
        val published = target.snapshot()

        source.flipBool(64)
        source.setInt(0, 7)
        source.setReal(0, 1.5)
        source.copyInto(target)

        assertEquals(
            Sample(BooleanArray(65) { it == 64 }, longArrayOf(3), doubleArrayOf(0.5)),
            published,
        )
        assertEquals(source.snapshot(), target.snapshot())
    }
}
