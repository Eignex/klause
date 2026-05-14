package com.eignex.klause.solver

import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictMinimisationTest {

    @Test
    fun `irrelevant pins are dropped from the conflict set`() {
        // (x ∨ y); pin x=false y=false z=true (z is unrelated).
        // Unminimised conflict already excludes z because z's bit isn't in the clause's
        // currentReason. This test exercises the post-pass path explicitly anyway.
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val r = p.propagate(
            Assumptions(bools = mapOf(0 to false, 1 to false, 2 to true)),
            minimizeConflict = true,
        )
        val u = assertIs<PropagationResult.Unsat>(r)
        assertEquals(setOf(0, 1), u.conflictBools)
    }

    @Test
    fun `over-conservative conflict is shrunk by drop-and-retry`() {
        // A single clause (x ∨ y) doesn't itself involve var 2, but a second clause
        // (x ∨ z) shares only x with the first. With pins x=false y=false z=false,
        // both clauses fail; raw conflict may include all three (depending on which
        // factor fires first), but minimisation should reveal {0, 1} or {0, 2} suffice.
        val p = Problem(
            numBoolVars = 3, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        val r = p.propagate(
            Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false)),
            minimizeConflict = true,
        )
        val u = assertIs<PropagationResult.Unsat>(r)
        // The minimised conflict must itself be jointly infeasible.
        val verify = p.propagate(
            Assumptions(
                bools = mapOf(0 to false, 1 to false, 2 to false).filterKeys { it in u.conflictBools }
            )
        )
        assertIs<PropagationResult.Unsat>(verify)
        // And it must be smaller than 3 (otherwise minimisation did nothing useful here).
        assertTrue(u.conflictBools.size < 3, "expected shrink, got ${u.conflictBools}")
    }

    @Test
    fun `removing any minimised element makes the problem feasible`() {
        val p = Problem(
            numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        val r = p.propagate(
            Assumptions(bools = mapOf(0 to false, 1 to false, 2 to true, 3 to false)),
            minimizeConflict = true,
        )
        val u = assertIs<PropagationResult.Unsat>(r)
        for (member in u.conflictBools) {
            val reduced = (u.conflictBools - member).associateWith {
                mapOf(0 to false, 1 to false, 2 to true, 3 to false).getValue(it)
            }
            val r2 = p.propagate(Assumptions(bools = reduced))
            assertIs<PropagationResult.Implied>(r2)
        }
    }

    @Test
    fun `int conflict members are minimised`() {
        // x + y ≤ 1; pin x=1, y=1, z=2 (z untouched by the factor).
        val p = Problem(
            numBoolVars = 0, numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val r = p.propagate(
            Assumptions(ints = mapOf(0 to 1, 1 to 1, 2 to 2)),
            minimizeConflict = true,
        )
        val u = assertIs<PropagationResult.Unsat>(r)
        assertEquals(setOf(0, 1), u.conflictInts)
    }

    @Test
    fun `no-op when result is Implied`() {
        val p = Problem(2, 0, emptyArray(), emptyList())
        val r = p.propagate(Assumptions(bools = mapOf(0 to true)), minimizeConflict = true)
        assertIs<PropagationResult.Implied>(r)
    }
}
