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

    private fun intRefs(vararg vars: Int) = IntArray(vars.size) { Term.ofIntVar(vars[it]) }
    private fun litRefs(vararg lits: Int) = IntArray(lits.size) { Term.ofLit(lits[it]) }
    private fun refsOf(r: LinearRow) = IntArray(r.size) { r.ref(it) }
    private fun coeffsOf(r: LinearRow) = LongArray(r.size) { r.coeff(it) }

    @Test
    fun `Linear exposes its own row exactly`() {
        val rows = Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 5).linearRows
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.LE, r.relation)
        assertEquals(5L, r.bound)
        assertTrue(r.isIntegerOnly)
        assertTrue(refsOf(r).contentEquals(intRefs(0, 1)))
        assertTrue(coeffsOf(r).contentEquals(longArrayOf(2, -1)))
    }

    @Test
    fun `Increasing exposes one ge row per adjacent pair`() {
        val rows = Increasing(intArrayOf(0, 1, 2), strict = true).linearRows
        assertEquals(2, rows.size)
        // xs(i+1) − xs(i) ≥ 1 for the strict chain.
        for (r in rows) {
            assertEquals(LinearOp.GE, r.relation)
            assertEquals(1L, r.bound)
            assertTrue(r.isIntegerOnly)
            assertTrue(coeffsOf(r).contentEquals(longArrayOf(1, -1)))
        }
        assertTrue(refsOf(rows[0]).contentEquals(intRefs(1, 0)))
        assertTrue(refsOf(rows[1]).contentEquals(intRefs(2, 1)))
    }

    @Test
    fun `Clause exposes the at-least-one row over its literals`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, false))
        val rows = Clause(lits).linearRows
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.GE, r.relation)
        assertEquals(1L, r.bound)
        assertTrue(!r.isIntegerOnly)
        assertTrue(refsOf(r).contentEquals(litRefs(*lits)))
        assertTrue(coeffsOf(r).contentEquals(longArrayOf(1, 1)))
    }

    @Test
    fun `Cardinality exposes its lower and upper bound rows`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        val rows = Cardinality(lits, min = 1, max = 2).linearRows
        assertEquals(2, rows.size)
        assertEquals(LinearOp.GE, rows[0].relation)
        assertEquals(1L, rows[0].bound)
        assertEquals(LinearOp.LE, rows[1].relation)
        assertEquals(2L, rows[1].bound)
        for (r in rows) assertTrue(refsOf(r).contentEquals(litRefs(*lits)))
    }

    @Test
    fun `PseudoBoolean exposes its weighted row`() {
        val lits = intArrayOf(Lit.make(0, true), Lit.make(1, true))
        val rows = PseudoBoolean(longArrayOf(3, 5), lits, PbOp.LE, 6).linearRows
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(LinearOp.LE, r.relation)
        assertEquals(6L, r.bound)
        assertTrue(refsOf(r).contentEquals(litRefs(*lits)))
        assertTrue(coeffsOf(r).contentEquals(longArrayOf(3, 5)))
    }

    @Test
    fun `Term references decode back to their variable or literal`() {
        assertTrue(!Term.isBool(Term.ofIntVar(7)))
        assertEquals(7, Term.intVar(Term.ofIntVar(7)))
        val lit = Lit.make(3, false)
        assertTrue(Term.isBool(Term.ofLit(lit)))
        assertEquals(lit, Term.lit(Term.ofLit(lit)))
    }

    @Test
    fun `a factor with no exact linear form is empty by default`() {
        // AllDifferent has no exact single-row linear form, so it keeps the empty default.
        assertTrue(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3).linearRows.isEmpty())
    }
}
