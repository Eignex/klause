package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared clause database behind the SAT-part passes. `eligible` is the safety gate that stops BVE
 * and BCE from rewriting a Boolean they cannot reconstruct, so each test pins one reason a variable
 * loses eligibility, plus the slot bookkeeping the passes read back through [SatClauseDb.toDelta].
 */
class SatClauseDbTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)

    private fun db(numBool: Int, factors: List<Factor>, objectiveBoolVars: Set<Int> = emptySet()) =
        SatClauseDb.build(Problem(numBool, 0, emptyArray(), factors), objectiveBoolVars)

    @Test
    fun `a variable appearing only in clean clauses is eligible`() {
        val d = db(2, listOf(Clause(intArrayOf(pos(0), pos(1)))))
        assertTrue(d.eligible[0] && d.eligible[1], "pure-clause variables are safe to eliminate")
    }

    @Test
    fun `a variable touched by a non-clause factor is ineligible`() {
        // b0 is also in a cardinality, which no SAT pass can reconstruct through; b2 stays clause-only.
        val d = db(
            3,
            listOf(
                Clause(intArrayOf(pos(0), pos(2))),
                Cardinality(intArrayOf(pos(0), pos(1)), min = 1, max = 1),
            ),
        )
        assertFalse(d.eligible[0], "a variable a non-clause factor mentions must not be eliminable")
        assertFalse(d.eligible[1], "the cardinality's other variable is equally unreachable")
        assertTrue(d.eligible[2], "an untouched clause-only variable stays eligible")
    }

    @Test
    fun `an objective variable is ineligible`() {
        val d = db(2, listOf(Clause(intArrayOf(pos(0), pos(1)))), objectiveBoolVars = setOf(0))
        assertFalse(d.eligible[0], "an objective variable must keep its value")
        assertTrue(d.eligible[1])
    }

    @Test
    fun `a tautological clause never enters a slot and is dropped by the delta`() {
        // (b0 ∨ !b0) is always true: no slot is created for it and the delta retires the input factor.
        val d = db(2, listOf(Clause(intArrayOf(pos(0), neg(0))), Clause(intArrayOf(pos(1)))))
        assertEquals(1, d.slotCount, "only the non-tautological clause takes a slot")
        assertEquals(1, d.origin(0), "the surviving slot points at input factor 1")
        assertContentEquals(intArrayOf(0), d.toDelta(null).droppedIndices, "the tautology is dropped")
    }

    @Test
    fun `the occurrence index maps a literal to the slots mentioning it`() {
        val d = db(2, listOf(Clause(intArrayOf(pos(0), pos(1))), Clause(intArrayOf(neg(0), pos(1)))))
        assertContentEquals(intArrayOf(0), d.occ(pos(0)).toIntArray())
        assertContentEquals(intArrayOf(1), d.occ(neg(0)).toIntArray())
        assertContentEquals(intArrayOf(0, 1), d.occ(pos(1)).toIntArray())
    }

    @Test
    fun `the delta drops consumed inputs and adds surviving derived clauses`() {
        val d = db(3, listOf(Clause(intArrayOf(pos(0), pos(1))), Clause(intArrayOf(neg(0), pos(2)))))
        d.remove(0)
        val derived = d.add(intArrayOf(pos(1), pos(2)))
        assertNull(d.clause(0), "a removed slot reads back as absent")
        assertEquals(-1, d.origin(derived), "a derived clause has no input origin")

        val delta = d.toDelta(null)
        assertContentEquals(intArrayOf(0), delta.droppedIndices, "only the consumed input is dropped")
        assertContentEquals(
            listOf(listOf(pos(1), pos(2))),
            delta.addedFactors.map { (it as Clause).literals.toList() },
        )
    }

    @Test
    fun `a derived clause removed before the delta contributes nothing`() {
        val d = db(2, listOf(Clause(intArrayOf(pos(0), pos(1)))))
        d.remove(d.add(intArrayOf(neg(0))))
        assertTrue(d.toDelta(null).isEmpty, "an added-then-removed clause leaves the delta empty")
    }
}
