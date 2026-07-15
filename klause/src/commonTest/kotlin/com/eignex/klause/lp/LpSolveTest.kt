package com.eignex.klause.lp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpSolveTest {

    @Test
    fun `a feasible LP certifies an optimum matching the float optimum`() {
        // minimize x + y  subject to  x + y >= 3,  0 <= x, y <= 5.
        val b = LpBuilder()
        b.addVar(0L, 5L, cost = 1L)
        b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.GE, 3L)
        val model = b.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.OPTIMAL, result.verdict)
        assertNotNull(result.certificate)
        assertNull(result.farkasRay)
        assertEquals(3L, result.exactLowerBound)
        val float = assertNotNull(result.float)
        val safe = assertNotNull(result.safeLowerBound)
        assertTrue(safe <= float.objective + 1e-6, "safe bound $safe exceeds the optimum ${float.objective}")
    }

    @Test
    fun `an infeasible LP is certified infeasible by a Farkas ray`() {
        // 0 <= x <= 1 with x >= 2 has no feasible point.
        val b = LpBuilder()
        b.addVar(0L, 1L, cost = 1L)
        b.addRow(intArrayOf(0), longArrayOf(1L), Relation.GE, 2L)
        val model = b.build(Sense.MINIMIZE)

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNotNull(result.farkasRay)
        assertNull(result.float)
        assertNull(result.certificate)
    }
}
