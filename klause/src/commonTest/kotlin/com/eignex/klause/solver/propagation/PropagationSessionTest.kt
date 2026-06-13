package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PropagationSessionTest {

    @Test
    fun `push pins agree with one-shot propagate`() {
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        val r = s.pinBool(2, false)
        // After pinning x0=true, the clause forces x1=true (already implied before pinning x2).
        // Pinning x2 doesn't add anything new.
        assertIs<PropagationResult.Implied>(r)
        val oneShot = p.propagate(Assumptions(bools = mapOf(0 to true, 2 to false)))
        assertIs<PropagationResult.Implied>(oneShot)
        assertEquals(true, oneShot.bools[1])
    }

    @Test
    fun `pop restores feasibility`() {
        // (x0 ∨ x1). After pinning x0=false x1=false → Unsat. Pop one → feasible again.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        assertIs<PropagationResult.Implied>(s.seed(Assumptions.None))
        assertIs<PropagationResult.Implied>(s.pinBool(0, false))
        // Forces x1=true. Now if we pin x1=false:
        val conflict = s.pinBool(1, false)
        assertIs<PropagationResult.Unsat>(conflict)
        s.popLast()
        val r = s.pinBool(1, true)
        assertIs<PropagationResult.Implied>(r)
    }

    @Test
    fun `currentAssumptions reflects trail`() {
        val p = Problem(2, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        s.pinBool(1, false)
        assertEquals(Assumptions(bools = mapOf(0 to true, 1 to false)), s.currentAssumptions())
        s.popLast()
        assertEquals(Assumptions(bools = mapOf(0 to true)), s.currentAssumptions())
    }

    @Test
    fun `popUntilUnpinned pops to target`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        s.pinBool(1, true)
        s.pinBool(2, true)
        s.popUntilUnpinned(VarKind.Bool, 1)
        // After popping until 1 is unpinned: 1 and 2 should both be gone (LIFO order).
        assertEquals(Assumptions(bools = mapOf(0 to true)), s.currentAssumptions())
    }

    @Test
    fun `seed propagates clause implications`() {
        // (x0 ∨ x1) — seed with x0=false; expect implied {1: true}.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        val r = s.seed(Assumptions(bools = mapOf(0 to false)))
        val impl = assertIs<PropagationResult.Implied>(r)
        assertEquals(true, impl.bools[1])
    }

    @Test
    fun `push reports only newly implied facts`() {
        // exactly-one over 3 vars. Pinning x0=true implies x1=false, x2=false at the
        // first push. A no-op subsequent push must report empty new facts.
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
            ),
        )
        val p = Problem(3, 0, emptyArray(), listOf(factor))
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        val first = s.pinBool(0, true)
        val firstImpl = assertIs<PropagationResult.Implied>(first)
        assertEquals(false, firstImpl.bools[1])
        assertEquals(false, firstImpl.bools[2])
        // Idempotent re-push: no new facts.
        val second = s.pinBool(0, true)
        val secondImpl = assertIs<PropagationResult.Implied>(second)
        assertTrue(secondImpl.bools.isEmpty())
        assertTrue(secondImpl.ints.isEmpty())
    }
}
