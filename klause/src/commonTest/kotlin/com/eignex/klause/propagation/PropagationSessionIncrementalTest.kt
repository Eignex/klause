package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PropagationSessionIncrementalTest {

    @Test
    fun `push-pop equals fresh state`() {
        // Pin x, propagate, snapshot. Pop. Repeat the same push. State must equal the
        // post-first-push state — confirms snapshot/restore is faithful.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        val firstPush = assertIs<PropagationResult.Implied>(s.pinBool(0, true))
        s.popLast()
        val secondPush = assertIs<PropagationResult.Implied>(s.pinBool(0, true))
        assertEquals(firstPush, secondPush)
    }

    @Test
    fun `popToLevel mid-stack restores intermediate fixpoint`() {
        // Push 3 decisions, pop to 1, push a different 2nd decision. State must reflect
        // {decision 1, new decision 2}, no leftover from the old level-2 / level-3 pins.
        val p = Problem(5, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        s.pinBool(1, true)
        s.pinBool(2, true)
        assertEquals(3, s.decisionLevel)
        s.popToLevel(1)
        assertEquals(1, s.decisionLevel)
        assertEquals(Assumptions(bools = mapOf(0 to true)), s.currentAssumptions())
        s.pinBool(3, false)
        assertEquals(2, s.decisionLevel)
        assertEquals(
            Assumptions(bools = mapOf(0 to true, 3 to false)),
            s.currentAssumptions(),
        )
    }

    @Test
    fun `conflict leaves session at pre-push level`() {
        // (x ∨ y). Pin x=false (forces y=true), then attempt y=false → Unsat.
        // After Unsat return, decisionLevel must equal 1 (the failed push didn't stick).
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, false)
        assertEquals(1, s.decisionLevel)
        val u = assertIs<PropagationResult.Unsat>(s.pinBool(1, false))
        assertEquals(setOf(1, 2), u.conflictLevels.toSet())
        assertEquals(1, s.decisionLevel, "failed push must not be on the trail")
        assertEquals(Assumptions(bools = mapOf(0 to false)), s.currentAssumptions())
        // Subsequent push of the (forced) alternate value must succeed.
        assertIs<PropagationResult.Implied>(s.pinBool(1, true))
    }

    @Test
    fun `incremental cumulative state matches one-shot propagate`() {
        // After a sequence of pinBool calls, the cumulative state should match what
        // problem.propagate would return for the same assumption set.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            ),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true)
        val asm = s.currentAssumptions()
        val oneShot = assertIs<PropagationResult.Implied>(p.propagate(asm))
        // After pinning x0 = true, the implication chain forces x1, x2, x3 all true.
        assertEquals(true, oneShot.bools[1])
        assertEquals(true, oneShot.bools[2])
        assertEquals(true, oneShot.bools[3])
    }

    @Test
    fun `int propagation incremental push`() {
        // x + y + z ≤ 2; pin x=1, then y=1; z must be tightened to ≤ 0.
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 2)),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinInt(0, 1)
        val r = assertIs<PropagationResult.Implied>(s.pinInt(1, 1))
        // After both pins, z is forced to 0 (= 0..0 by propagation).
        assertEquals(0, r.ints[2])
    }

    @Test
    fun `deep push-pop chain does not accumulate state`() {
        // Many push/pop cycles — verify the trail and snapshots don't grow without bound.
        val p = Problem(10, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        repeat(50) {
            for (i in 0 until 5) s.pinBool(i, true)
            assertEquals(5, s.decisionLevel)
            s.popToLevel(0)
            assertEquals(0, s.decisionLevel)
        }
        assertEquals(Assumptions.None, s.currentAssumptions())
    }

    @Test
    fun `seed conflict returns Unsat with seed levels`() {
        // (x ∨ y) seeded with x=false y=false directly: conflict detected during seed.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        val u = assertIs<PropagationResult.Unsat>(
            s.seed(Assumptions(bools = mapOf(0 to false, 1 to false))),
        )
        assertEquals(setOf(0, 1), u.conflictBools.toSet())
        // After Unsat, the session should be at the pre-conflict level (level 1 — just x).
        assertEquals(1, s.decisionLevel)
    }

    @Test
    fun `re-seed clears prior trail`() {
        val p = Problem(3, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions(bools = mapOf(0 to true)))
        s.pinBool(1, false)
        assertEquals(2, s.decisionLevel)
        s.seed(Assumptions(bools = mapOf(2 to true)))
        assertEquals(1, s.decisionLevel)
        assertEquals(Assumptions(bools = mapOf(2 to true)), s.currentAssumptions())
    }

    @Test
    fun `popLast on empty trail is a no-op`() {
        val p = Problem(2, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.popLast()
        assertEquals(0, s.decisionLevel)
    }

    @Test
    fun `mixed bool-int incremental session`() {
        // Two factors: clause (b0 ∨ b1) and linear x + y ≤ 1 over int domains.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
            ),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, false) // forces b1=true
        s.pinInt(0, 1) // forces y ≤ 0 → tightens to {0}
        val u = assertIs<PropagationResult.Unsat>(s.pinInt(1, 1)) // 1+1 > 1
        assertEquals(2, s.decisionLevel, "failed push must not stick")
        // The conflict came from levels involving the int pins (levels 2 and 3).
        assertEquals(setOf(2, 3), u.conflictLevels.toSet())
    }

    @Test
    fun `exactlyOne chain solved by decisions`() {
        // exactly-one over 4 vars. Pin three to false → the fourth is implied true.
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                        Lit.make(3, true),
                    ),
                ),
            ),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, false)
        s.pinBool(1, false)
        val r = assertIs<PropagationResult.Implied>(s.pinBool(2, false))
        assertEquals(true, r.bools[3])
    }
}
