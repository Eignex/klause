package com.eignex.klause.propagation
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropagationSessionDecisionLevelTest {

    @Test
    fun `conflict levels identify the responsible decisions`() {
        // (x ∨ y). Pin x=false at level 1, z=true at level 2, y=false at level 3 → Unsat.
        // The clause's vars are {0, 1} → conflictLevels = {1, 3}. Level 2 (z) is irrelevant.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertIs<PropagationResult.Implied>(s.pinBool(0, false)) // level 1
        assertIs<PropagationResult.Implied>(s.pinBool(2, true)) // level 2
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false)) // level 3 → Unsat
        assertEquals(setOf(1, 3), u.conflictLevels.toSet())
        assertEquals(setOf(0, 1), u.conflictBools.toSet())
        assertTrue(2 !in u.conflictBools, "z at level 2 was irrelevant")
    }

    @Test
    fun `propagated implications inherit deepest contributing level`() {
        // Two-step propagation: pin x=true (level 1), pin z=true (level 2). Clause
        // (¬x ∨ y) forces y=true at level 1 (since y was derived from x alone, z is
        // unrelated). Then pinning y=false at level 3 → conflictLevels = {1, 3}.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertIs<PropagationResult.Implied>(s.pinBool(0, true)) // level 1; forces y=true
        assertIs<PropagationResult.Implied>(s.pinBool(2, true)) // level 2; irrelevant
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false)) // level 3
        // 1 (decision for x, which propagated y) and 3 (the explicit y=false attempt).
        assertEquals(setOf(1, 3), u.conflictLevels.toSet())
    }

    @Test
    fun `seed pins occupy levels 1 through N`() {
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val u = assertIs<PropagationResult.Unsat>(
            p.propagate(Assumptions(bools = mapOf(0 to false, 1 to false))),
        )
        // Both seed pins are responsible — both at levels {1, 2}.
        assertEquals(setOf(1, 2), u.conflictLevels.toSet())
        assertEquals(setOf(0, 1), u.conflictBools.toSet())
    }
}
