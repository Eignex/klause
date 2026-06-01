package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Coverage for [ReifiedCardinality]'s propagator with focus on the `¬aux` branch (body
 * must NOT hold) — historically a no-op, now full forcing when one of the two escape
 * directions is uniquely feasible.
 */
class ReifiedCardinalityPropTest {

    @Test
    fun `aux false with up-only escape forces all unassigned true`() {
        // ReifiedCardinality(aux, lits, min=1, max=2). 4 literals over distinct bool
        // vars. With aux pinned false and v0=true forced, trueCount = 1 = min. The
        // "down" branch (count < 1) is infeasible (we'd need 0 trues but already have 1);
        // only "count > max = 2" is feasible. Required additional trues: max-trueCount+1
        // = 2 = unassigned. So every unassigned literal must be forced true.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                // Force v0 = true.
                Clause(intArrayOf(Lit.make(0, true))),
                // ReifiedCardinality: aux=v3, lits=[v0, v1, v2], min=1, max=2.
                ReifiedCardinality(
                    auxBoolVar = 3,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        // Pin aux to false externally.
        val session = PropagationSession(problem)
        val r = session.pinBool(3, false)
        val implied = assertIs<PropagationResult.Implied>(r)
        // Expect: v1 and v2 forced true (so count = 3 > max = 2).
        assertEquals(true, session.boolValue(1), "v1 should be forced true")
        assertEquals(true, session.boolValue(2), "v2 should be forced true")
        // Silence unused warning.
        @Suppress("UNUSED_VARIABLE")
        val r = implied
    }

    @Test
    fun `aux false with down-only escape forces all unassigned false`() {
        // Force "down-only" escape: trueCount = min - 1 (so cap == 0, must avoid any
        // more trues), and trueCount + unassigned ≤ max (up-branch infeasible). Easiest
        // satisfiable shape:
        //   min=1, max=2 (so up-branch needs count > 2, i.e., ≥ 3 trues), 2 literals,
        //   trueCount=0 (so cap = min - 0 - 1 = 0). Up-branch needs 3 trues > 2
        //   unassigned → infeasible. cap == 0 → both literals forced false.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, false)
        val implied = assertIs<PropagationResult.Implied>(r)
        // Expect v0 and v1 forced false.
        assertEquals(false, session.boolValue(0), "v0 should be forced false")
        assertEquals(false, session.boolValue(1), "v1 should be forced false")
        @Suppress("UNUSED_VARIABLE")
        val r = implied
    }

    @Test
    fun `aux false conflicts with body-must-hold via definitelyIn`() {
        // Body must hold under current pins (count ∈ [min, max] forced) AND aux is
        // pinned false. ReifiedCardinality's `definitelyIn` check pins aux=true, which
        // conflicts with the prior aux=false pin → Unsat surfaced via revertAndUnsat.
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                ReifiedCardinality(
                    auxBoolVar = 2,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true)),
                    min = 1,
                    max = 2,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(2, false)
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `aux true unchanged - boundary forcing still fires`() {
        // Sanity: the aux=true case still pins all unassigned to !pos when trueCount == max.
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))), // force v0 = true
                ReifiedCardinality(
                    auxBoolVar = 3,
                    literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                    min = 0,
                    max = 1,
                ),
            ),
        )
        val session = PropagationSession(problem)
        val r = session.pinBool(3, true)
        assertIs<PropagationResult.Implied>(r)
        // After: trueCount = 1 = max. Remaining unassigned (v1, v2) must be false.
        assertEquals(false, session.boolValue(1), "v1 should be forced false")
        assertEquals(false, session.boolValue(2), "v2 should be forced false")
    }
}
