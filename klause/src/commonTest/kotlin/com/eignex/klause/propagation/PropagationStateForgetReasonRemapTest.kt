package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the php8 crash: [PropagationState.forgetLearnedClauses] renumbers and drops
 * learned clauses, but the per-variable reason fields ([PropagationState.boolReason] etc.) can
 * hold a learned-clause id for a level-0 fact that survives a restart's pop-to-root. If those
 * reasons aren't remapped, the next conflict's [PropagationState.extractConflictFactors]
 * dereferences a stale learned id through [PropagationState.factorAt] and indexes past the
 * compacted clause array (`IndexOutOfBoundsException: Index … out of bounds for length …`).
 */
class PropagationStateForgetReasonRemapTest {

    private fun state(): PropagationState {
        val p = Problem(
            numBoolVars = 4,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 4)),
            // Two static factors so learned-clause ids start at numFactors = 2.
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )
        return PropagationState(p, Assumptions.None)
    }

    @Test
    fun `forget remaps learned reason ids and clears dropped ones`() {
        val s = state()
        val base = s.problem.numFactors // 2
        // Four learned clauses → factor ids base+0 .. base+3.
        val fids = IntArray(4) { i ->
            s.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make((i % 4), false))), lbd = 2)
        }
        // Point reason fields at learned clauses: one that will be kept-and-renumbered, one
        // that will be dropped.
        s.boolReason[0] = fids[3] // learned idx 3 — kept, renumbered
        s.boolReason[1] = fids[2] // learned idx 2 — dropped
        s.intMinReason[0] = fids[3] // kept, renumbered
        s.intMaxReason[0] = fids[2] // dropped
        s.boolReason[2] = 0 // a static factor id — must pass through untouched

        // Drop learned index 2; keep 0,1,3 → remap 0→0,1→1,2→-1,3→2.
        s.forgetLearnedClauses { idx, _ -> idx != 2 }

        assertEquals(base + 2, s.boolReason[0], "kept learned reason must rewrite to its new id")
        assertEquals(-1, s.boolReason[1], "dropped learned reason must clear to -1")
        assertEquals(base + 2, s.intMinReason[0], "kept int-min learned reason must rewrite")
        assertEquals(-1, s.intMaxReason[0], "dropped int-max learned reason must clear")
        assertEquals(0, s.boolReason[2], "static factor reason must pass through unchanged")

        // The remapped reason must be a live factor id — factorAt must not index out of bounds
        // (this is exactly the dereference that crashed in extractConflictFactors on php8).
        s.factorAt(s.boolReason[0])
        s.factorAt(s.intMinReason[0])
    }
}
