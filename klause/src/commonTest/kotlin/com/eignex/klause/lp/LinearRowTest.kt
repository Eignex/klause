package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearRowTest {

    @Test
    fun `Linear exposes its own row exactly`() {
        val rows = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 5).linearRows
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.LE, r.op)
        assertEquals(5L, r.bound)
        assertTrue(r.vars.contentEquals(intArrayOf(0, 1)))
        assertTrue(r.coeffs.contentEquals(longArrayOf(2, -1)))
        assertTrue(r.boolLits.isEmpty())
    }

    @Test
    fun `Increasing exposes one ge row per adjacent pair`() {
        val rows = Increasing(intArrayOf(0, 1, 2), strict = true).linearRows
        assertEquals(2, rows.size)
        // xs(i+1) − xs(i) ≥ 1 for the strict chain.
        for (r in rows) {
            assertEquals(LinearOp.GE, r.op)
            assertEquals(1L, r.bound)
            assertTrue(r.coeffs.contentEquals(longArrayOf(1, -1)))
        }
        assertTrue(rows[0].vars.contentEquals(intArrayOf(1, 0)))
        assertTrue(rows[1].vars.contentEquals(intArrayOf(2, 1)))
    }

    @Test
    fun `Clause exposes the at-least-one row over its literals`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, false))
        val rows = Clause(lits).linearRows
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.GE, r.op)
        assertEquals(1L, r.bound)
        assertTrue(r.vars.isEmpty())
        assertTrue(r.boolLits.contentEquals(lits))
        assertTrue(r.boolCoeffs.contentEquals(longArrayOf(1, 1)))
    }

    @Test
    fun `Cardinality exposes its lower and upper bound rows`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        val rows = Cardinality(lits, min = 1, max = 2).linearRows
        assertEquals(2, rows.size)
        assertEquals(LinearOp.GE, rows[0].op)
        assertEquals(1L, rows[0].bound)
        assertEquals(LinearOp.LE, rows[1].op)
        assertEquals(2L, rows[1].bound)
        for (r in rows) assertTrue(r.boolLits.contentEquals(lits))
    }

    @Test
    fun `PseudoBoolean exposes its weighted row`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true))
        val rows = PseudoBoolean(longArrayOf(3, 5), lits, PbOp.LE, 6).linearRows
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.LE, r.op)
        assertEquals(6L, r.bound)
        assertTrue(r.boolLits.contentEquals(lits))
        assertTrue(r.boolCoeffs.contentEquals(longArrayOf(3, 5)))
    }

    @Test
    fun `a factor with no exact linear form is empty by default`() {
        // AllDifferent has no exact single-row linear form, so it keeps the empty default.
        assertTrue(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3).linearRows.isEmpty())
    }
}
