package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
