package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinCostAssignmentTest {

    @Test
    fun `simple assignment picks the cheapest distinct values`() {
        // 2 vars, 2 values; the cheap diagonal (0,1) costs 1+1, the anti-diagonal 5+5.
        val a = MinCostAssignment(2, 2)
        a.addOption(0, 0, 1)
        a.addOption(0, 1, 5)
        a.addOption(1, 0, 5)
        a.addOption(1, 1, 1)
        val r = a.solve()
        assertTrue(r.feasible)
        assertEquals(2L, r.cost)
        assertEquals(0, r.assignedValue[0])
        assertEquals(1, r.assignedValue[1])
    }

    @Test
    fun `detects infeasibility when no perfect matching exists`() {
        // Both variables can only take value 0 — no distinct assignment.
        val a = MinCostAssignment(2, 2)
        a.addOption(0, 0, 1)
        a.addOption(1, 0, 1)
        assertTrue(!a.solve().feasible)
    }

    @Test
    fun `handles negative costs`() {
        val a = MinCostAssignment(2, 3)
        a.addOption(0, 0, -3)
        a.addOption(0, 1, -1)
        a.addOption(1, 1, -1)
        a.addOption(1, 2, -5)
        val r = a.solve()
        assertTrue(r.feasible)
        assertEquals(-8L, r.cost) // var0→val0 (-3), var1→val2 (-5)
    }

    @Test
    fun `large representable costs preserve feasible assignments`() {
        for (cost in longArrayOf(Long.MAX_VALUE / 4 - 1, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4 + 1, Long.MAX_VALUE)) {
            val assignment = MinCostAssignment(1, 1)
            assignment.addOption(0, 0, cost)

            val result = assignment.solve()

            assertTrue(result.feasible, "cost $cost")
            assertEquals(cost, result.cost)
            assertEquals(0, result.assignedValue[0])
        }
    }

    @Test
    fun `unrepresentable reverse costs decline through checked overflow`() {
        val assignment = MinCostAssignment(1, 1)

        assertFailsWith<CheckedLongOverflowException> {
            assignment.addOption(0, 0, Long.MIN_VALUE)
        }
    }
}
