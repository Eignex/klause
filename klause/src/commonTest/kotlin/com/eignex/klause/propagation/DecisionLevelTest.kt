package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.VarKind
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecisionLevelTest {

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
    fun `constraint-only Unsat has empty levels`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val u = assertIs<PropagationResult.Unsat>(p.propagate())
        assertEquals(emptySet(), u.conflictLevels.toSet())
        assertEquals(emptySet(), u.conflictBools.toSet())
    }

    @Test
    fun `popToLevel restores feasibility`() {
        // (x ∨ y). Pin x=false then y=false → Unsat at level 2. popToLevel(1) drops y,
        // leaving x=false pinned; propagation now implies y=true.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertIs<PropagationResult.Implied>(s.pinBool(0, false))
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false))
        assertEquals(setOf(1, 2), u.conflictLevels.toSet())
        s.popToLevel(1)
        assertEquals(1, s.decisionLevel)
        // Now pinning y=true succeeds.
        assertIs<PropagationResult.Implied>(s.pinBool(1, true))
    }

    @Test
    fun `decision level grows with each push`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertEquals(0, s.decisionLevel)
        s.pinBool(0, true)
        assertEquals(1, s.decisionLevel)
        s.pinBool(1, false)
        assertEquals(2, s.decisionLevel)
        s.popLast()
        assertEquals(1, s.decisionLevel)
    }

    @Test
    fun `popToLevel zero clears all decisions`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        s.pinBool(1, false)
        s.pinBool(2, true)
        assertEquals(3, s.decisionLevel)
        s.popToLevel(0)
        assertEquals(0, s.decisionLevel)
        assertEquals(Assumptions.None, s.currentAssumptions())
    }

    @Test
    fun `decisionAt returns the var at each level`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(2, true)
        s.pinBool(0, false)
        assertEquals(VarKind.Bool to 2, s.decisionAt(1))
        assertEquals(VarKind.Bool to 0, s.decisionAt(2))
        assertNull(s.decisionAt(3))
        assertNull(s.decisionAt(0))
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
    fun `int decisions get their own level`() {
        // x + y ≤ 1. Pin x=1 at level 1, y=1 at level 2 → Unsat with both levels involved.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertIs<PropagationResult.Implied>(s.pinInt(0, 1))
        val u = assertIs<PropagationResult.Unsat>(s.pinInt(1, 1))
        assertEquals(setOf(1, 2), u.conflictLevels.toSet())
        assertEquals(setOf(0, 1), u.conflictInts.toSet())
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
