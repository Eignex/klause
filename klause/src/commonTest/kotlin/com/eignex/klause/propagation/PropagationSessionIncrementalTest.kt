package com.eignex.klause.propagation
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
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
    fun `reseedFrom leaves the session identical to a fresh seed of the same set`() {
        // Mixed bool/int with a forcing clause and a capacity row, so a re-seed carries implied facts.
        fun problem() = Problem(
            numBoolVars = 3,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
            ),
        )
        // (from, to) pairs spanning: full rebuild (no shared prefix), kept prefix + suffix swap,
        // and an identical set (everything kept, nothing pushed).
        val cases = listOf(
            Assumptions(bools = mapOf(0 to false)) to Assumptions(bools = mapOf(2 to true), ints = mapOf(0 to 3L)),
            Assumptions(bools = mapOf(0 to false, 2 to true)) to
                Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 3L)),
            Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 3L)) to
                Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 3L)),
        )
        for ((from, to) in cases) {
            val incremental = PropagationSession(problem())
            incremental.seed(from)
            val incrementalResult = incremental.reseedFrom(to)

            val fresh = PropagationSession(problem())
            val freshResult = fresh.seed(to)

            assertEquals(freshResult, incrementalResult, "implied facts diverge for $from → $to")
            assertEquals(fresh.currentAssumptions(), incremental.currentAssumptions(), "pin set diverges")
            assertEquals(fresh.decisionLevel, incremental.decisionLevel, "level count diverges")
        }
    }

    @Test
    fun `reseedFrom pops leftover search decisions above the seed`() {
        // Seed, then push extra decisions (as a search would), then re-seed a fragment: the leftover
        // decisions must not survive — the outcome equals a fresh seed of the new fragment.
        val p = Problem(5, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions(bools = mapOf(0 to true)))
        s.pinBool(3, true)
        s.pinBool(4, false)
        assertEquals(3, s.decisionLevel)
        s.reseedFrom(Assumptions(bools = mapOf(0 to true, 1 to false)))
        assertEquals(2, s.decisionLevel, "leftover search decisions must be gone")
        assertEquals(Assumptions(bools = mapOf(0 to true, 1 to false)), s.currentAssumptions())
    }

    @Test
    fun `reseedFrom onto an infeasible fragment returns Unsat`() {
        // (x ∨ y); re-seed both false → conflict, mirroring seed's Unsat contract.
        val p = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions(bools = mapOf(0 to true)))
        assertIs<PropagationResult.Unsat>(s.reseedFrom(Assumptions(bools = mapOf(0 to false, 1 to false))))
    }

}
