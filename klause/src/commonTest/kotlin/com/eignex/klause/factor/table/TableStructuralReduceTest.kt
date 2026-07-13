package com.eignex.klause.factor.table

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableStructuralReduceTest {

    // Tuples over vars {0,1}: (0,0),(0,1),(1,0),(1,1).
    private val allPairs = Table(intArrayOf(0, 1), longArrayOf(0, 0, 0, 1, 1, 0, 1, 1))

    @Test
    fun `drops tuples with a cell outside the current domain`() {
        // x0 pinned to 0 ⇒ only (0,0) and (0,1) survive.
        val r = allPairs.structuralReduce(arrayOf(IntDomain(0, 0), IntDomain(0, 1)))
        assertTrue(r is FactorReduction.Rewrite)
        val table = (r as FactorReduction.Rewrite).replacement.single() as Table
        assertEquals(2, table.numTuples)
        assertTrue(table.tuples.toList() == listOf(0L, 0L, 0L, 1L))
    }

    @Test
    fun `a single surviving tuple becomes equalities and drops the table`() {
        // x0 = 1, x1 = 0 ⇒ only (1,0) survives.
        val r = allPairs.structuralReduce(arrayOf(IntDomain(1, 1), IntDomain(0, 0)))
        assertTrue(r is FactorReduction.Rewrite)
        val repl = (r as FactorReduction.Rewrite).replacement
        assertTrue(repl.all { it is Linear }, "single tuple pins each variable via an equality")
        assertEquals(2, repl.size)
    }

    @Test
    fun `no dead tuple leaves the table unchanged`() {
        assertEquals(FactorReduction.Unchanged, allPairs.structuralReduce(arrayOf(IntDomain(0, 1), IntDomain(0, 1))))
    }

    @Test
    fun `a short-support table is not reduced`() {
        // hi != null (a `*`/range cell) ⇒ the ground-tuple reduction does not apply.
        val short = Table(intArrayOf(0, 1), longArrayOf(0, Long.MIN_VALUE), hi = longArrayOf(0, Long.MAX_VALUE))
        assertEquals(FactorReduction.Unchanged, short.structuralReduce(arrayOf(IntDomain(0, 1), IntDomain(0, 5))))
    }
}
