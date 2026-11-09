package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClausePrimalWalkTest {
    @Test
    fun `a failed cost trial should leave the next repair free to choose another variable`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(64, true))),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(65, true))),
            Clause(intArrayOf(Lit.make(64, false), Lit.make(65, false))),
            Clause(intArrayOf(Lit.make(63, true), Lit.make(64, true), Lit.make(65, true))),
        )
        val walk = ClausePrimalWalk(Sample(BooleanArray(66) { it == 0 || it == 63 }, longArrayOf()),
            clauses, listOf(0, 63), Assumptions.None, 3L, null)

        walk.begin(0, Cancellation.Never)
        assertEquals(false, walk.advance(Cancellation.Never, -1L))
        walk.begin(63, Cancellation.Never)
        assertEquals(true, walk.advance(Cancellation.Never, -1L))
        val sample = walk.sample()

        assertTrue(sample.bools[0])
        assertTrue(!sample.bools[63])
        assertTrue(sample.bools[64] xor sample.bools[65])
    }

    @Test
    fun `a repair paused before its first move should resume the same trial`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val walk = ClausePrimalWalk(
            Sample(booleanArrayOf(true, false, false), longArrayOf()),
            clauses,
            listOf(0),
            Assumptions.None,
            3L,
            null,
        )

        walk.begin(0, Cancellation.Never)
        assertEquals(null, walk.advance(Cancellation.Never, 0L))
        assertEquals(true, walk.advance(Cancellation.Never, -1L))
        val sample = walk.sample()

        assertTrue(!sample.bools[0])
        assertTrue(sample.bools[1] xor sample.bools[2])
    }

    @Test
    fun `unit repairs should preserve repeated literals and tautologies`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, true), Lit.make(1, false))),
        )
        val walk = ClausePrimalWalk(
            Sample(booleanArrayOf(true, false), longArrayOf()),
            clauses,
            listOf(0),
            Assumptions.None,
            3L,
            null,
        )

        walk.begin(0, Cancellation.Never)
        assertEquals(true, walk.advance(Cancellation.Never, -1L))
        val sample = walk.sample()

        assertTrue(!sample.bools[0])
        assertTrue(sample.bools[1])
        assertTrue(
            clauses.all { clause ->
                clause.literals.any { Lit.evaluate(it, sample.bools[Lit.variable(it)]) }
            },
        )
    }

    @Test
    fun `a failed repair should restore the feasible assignment and caller pins`() {
        val clauses = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))))
        val walk = ClausePrimalWalk(
            Sample(booleanArrayOf(true, false), longArrayOf()),
            clauses,
            listOf(0),
            Assumptions(bools = mapOf(1 to false)),
            3L,
            null,
        )

        walk.begin(0, Cancellation.Never)
        assertEquals(false, walk.advance(Cancellation.Never, -1L))
        val sample = walk.sample()

        assertTrue(sample.bools[0])
        assertTrue(!sample.bools[1])
    }

    @Test
    fun `clausal repair should retain the solve-wide move limit`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val walk = ClausePrimalWalk(
            Sample(booleanArrayOf(true, false, false), longArrayOf()),
            clauses,
            listOf(0),
            Assumptions.None,
            3L,
            NodeBudget(1L).apply { spend() },
        )
        walk.begin(0, Cancellation.Never)

        val outcome = walk.advance(Cancellation.Never, -1L)

        assertEquals(null, outcome)
    }

    @Test
    fun `clausal repair should use a move covered by the integer charge`() {
        val clauses = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        val budget = NodeBudget(1L)
        val walk = ClausePrimalWalk(
            Sample(booleanArrayOf(true, false, false), longArrayOf()),
            clauses,
            listOf(0),
            Assumptions.None,
            3L,
            budget,
        )
        walk.begin(0, Cancellation.Never)

        val outcome = walk.advance(Cancellation.Never, -1L)

        assertEquals(true, outcome)
        assertEquals(1L, budget.spent)
    }
}
