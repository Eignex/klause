package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictReasonTest {

    @Test
    fun `constraint-only Unsat has empty conflict sets`() {
        // Two clauses (x), (¬x): infeasible without any input.
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = p.propagate()
        val u = assertIs<PropagationResult.Unsat>(r)
        assertEquals(emptySet(), u.conflictBools.toSet())
        assertEquals(emptySet(), u.conflictInts.toSet())
    }

    @Test
    fun `conflicting input pins surface in conflict set`() {
        // x0 ∨ x1, with x0 = x1 = false pinned → Unsat with both inputs in the conflict set.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to true)))
        val u = assertIs<PropagationResult.Unsat>(r)
        // 0 and 1 are jointly responsible; 2 is irrelevant and should not appear.
        assertTrue(0 in u.conflictBools, "expected 0 in conflict, got $u")
        assertTrue(1 in u.conflictBools, "expected 1 in conflict, got $u")
        assertEquals(false, 2 in u.conflictBools, "var 2 is irrelevant")
        // Removing either of 0/1 should make the problem feasible again.
        val withoutZero = p.propagate(Assumptions(bools = mapOf(1 to false, 2 to true)))
        assertIs<PropagationResult.Implied>(withoutZero)
        val withoutOne = p.propagate(Assumptions(bools = mapOf(0 to false, 2 to true)))
        assertIs<PropagationResult.Implied>(withoutOne)
    }

    @Test
    fun `seeded conflicting input ints decode to conflict set`() {
        // Two int vars with domain {0..9}; AtMostOne over (x0=5, x1=5) is not directly a
        // single factor, so instead use a Linear: x0 + x1 ≤ 1 (so 0+0 or 0+1 or 1+0 feasible),
        // pin x0 = 1 and x1 = 1 → Unsat. Both inputs jointly responsible.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val r = p.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 1)))
        val u = assertIs<PropagationResult.Unsat>(r)
        assertTrue(0 in u.conflictInts)
        assertTrue(1 in u.conflictInts)
    }

    @Test
    fun `irrelevant assumption stays out of conflict set`() {
        // (x ∨ y), pin x=false y=false z=true. z is unrelated; conflict is {x, y}.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(2, true))),
            ),
        )
        val r = p.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to true)))
        val u = assertIs<PropagationResult.Unsat>(r)
        assertTrue(0 in u.conflictBools)
        assertTrue(1 in u.conflictBools)
        assertEquals(false, 2 in u.conflictBools)
    }
}
