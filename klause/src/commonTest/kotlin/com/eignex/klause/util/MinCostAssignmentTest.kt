package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinCostAssignmentTest {

    @Test
    fun `assignment should pick the cheapest distinct values`() {
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
    fun `assignment should be infeasible when no perfect matching exists`() {
        val a = MinCostAssignment(2, 2)
        a.addOption(0, 0, 1)
        a.addOption(1, 0, 1)
        assertTrue(!a.solve().feasible)
    }

    @Test
    fun `assignment should handle negative costs`() {
        val a = MinCostAssignment(2, 3)
        a.addOption(0, 0, -3)
        a.addOption(0, 1, -1)
        a.addOption(1, 1, -1)
        a.addOption(1, 2, -5)
        val r = a.solve()
        assertTrue(r.feasible)
        assertEquals(-8L, r.cost)
    }

    @Test
    fun `large representable costs should preserve feasible assignments`() {
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
    fun `unrepresentable reverse costs should decline through checked overflow`() {
        val assignment = MinCostAssignment(1, 1)

        assertFailsWith<CheckedLongOverflowException> {
            assignment.addOption(0, 0, Long.MIN_VALUE)
        }
    }
}
