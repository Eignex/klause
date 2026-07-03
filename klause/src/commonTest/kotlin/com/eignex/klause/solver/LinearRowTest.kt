package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Increasing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinearRowTest {

    @Test
    fun `Linear exposes its own row exactly`() {
        val rows = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 5).linearRows()
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.LE, r.op)
        assertEquals(5L, r.bound)
        assertTrue(r.vars.contentEquals(intArrayOf(0, 1)))
        assertTrue(r.coeffs.contentEquals(intArrayOf(2, -1)))
    }

    @Test
    fun `Increasing exposes one ge row per adjacent pair`() {
        val rows = Increasing(intArrayOf(0, 1, 2), strict = true).linearRows()
        assertEquals(2, rows.size)
        // xs(i+1) − xs(i) ≥ 1 for the strict chain.
        for (r in rows) {
            assertEquals(LinearOp.GE, r.op)
            assertEquals(1L, r.bound)
            assertTrue(r.coeffs.contentEquals(intArrayOf(1, -1)))
        }
        assertTrue(rows[0].vars.contentEquals(intArrayOf(1, 0)))
        assertTrue(rows[1].vars.contentEquals(intArrayOf(2, 1)))
    }

    @Test
    fun `a factor with no exact linear form returns null by default`() {
        // AllDifferent has no exact single-row linear form, so it keeps the null default.
        assertNull(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3).linearRows())
    }
}
