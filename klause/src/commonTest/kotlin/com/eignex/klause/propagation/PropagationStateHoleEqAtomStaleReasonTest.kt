package com.eignex.klause.propagation

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression for #670. An interior-hole eq atom (`[v = k]` ruled out by a carve at `min < k < max`)
 * rests on its carve-time trail slot. If a later, deeper bound move crosses `k` while `k` sits below
 * the min / above the max, single-establishment [wakeAtom] leaves that first (carve) slot intact
 * rather than overwriting it with the bound move's level and reason. Backtracking the bound move
 * widens it back across `k`, so `k` is an interior hole again; the reversible atom trail restores
 * exactly the carve-time slot. Were the slot instead left citing the bound move, it would name a
 * bound atom no longer determined, at a level no longer on the trail, and conflict analysis would
 * ingest that undetermined atom and abort the solve ("ingest atom N at lower level undetermined").
 *
 * The invariant this guards: an atom's derived antecedents ([atomAntecedentsDerived]) must cite only
 * atoms that are currently determined — never one whose truth is undetermined — for every determined
 * atom, after any push/pop sequence.
 */
class PropagationStateHoleEqAtomStaleReasonTest {

    private fun freshState(numVars: Int, hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = numVars,
            intDomains = Array(numVars) { IntDomain(0, hi.toLong()) },
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    /** Begin a fresh decision level on int var [v] with reason factor [fid]. */
    private fun PropagationState.beginLevel(v: Int, fid: Int) {
        levelToDecisionVar.add(problem.numBoolVars + v)
        currentLevel = levelToDecisionVar.size
        currentFactor = fid
    }

    /** No determined atom's derived antecedents may cite an undetermined atom (#670). */
    private fun assertReasonsCiteOnlyDeterminedAtoms(s: PropagationState, where: String) {
        for (id in 0 until s.atoms.intVar.size) {
            if (s.atomCurrentTruth(id) == null) continue // undetermined atoms have no live reason
            val reason = s.atomAntecedentsDerived(id) ?: continue
            for (lit in reason) {
                val v = Lit.variable(lit)
                if (v < s.problem.numBoolVars) continue
                val citedId = v - s.problem.numBoolVars
                assertNotNull(
                    s.atomCurrentTruth(citedId),
                    "at $where: determined atom $id (var=${s.atoms.intVar[id]} ${s.atoms.kind[id]} " +
                        "${s.atoms.threshold[id]}) cites undetermined atom $citedId " +
                        "(var=${s.atoms.intVar[citedId]} ${s.atoms.kind[citedId]} ${s.atoms.threshold[citedId]})",
                )
            }
        }
    }

    @Test
    fun `a hole eq atom keeps its carve reason after a bound move widens back`() {
        val s = freshState(numVars = 2, hi = 6)
        val eq2 = s.atomVarEq(0, 2)
        val eqId = eq2 - s.problem.numBoolVars

        // Level 1: establish [v1 <= 1] as the premise the carve will cite.
        s.beginLevel(1, fid = -1)
        assertTrue(s.tightenIntMax(1, 1))

        // Level 2: a factor carves value 2 out of v0 (interior: v0 is still [0..6]), citing [v1 <= 1].
        // [v0 = 2] becomes false with this carve as its trail-slot reason.
        s.beginLevel(0, fid = 0)
        val carveReason = intArrayOf(Lit.make(s.atomVarLe(1, 1), false))
        assertTrue(s.excludeIntValue(0, 2, carveReason))
        assertEquals(false, s.atomCurrentTruth(eqId))
        val mark2 = s.mark()

        // Level 3: a different factor raises v0's min to 3. The crossing re-wakes [v0 = 2] false and
        // overwrites its slot with this bound move's level (3) and reason (citing [v0 >= 3]).
        s.beginLevel(0, fid = 1)
        assertTrue(s.tightenIntMin(0, 3))
        assertEquals(false, s.atomCurrentTruth(eqId))

        // Backtrack to level 2: v0 widens back to [0..6]\{2}, so 2 is an interior hole again. The slot
        // must revert to deriving from the carve record, not keep the now-widened-away bound reason.
        s.undoTo(mark2)
        assertEquals(false, s.atomCurrentTruth(eqId), "hole at 2 still holds after backtrack")

        // The derived reason must be the carve premise [v1 <= 1] (determined true), not the stale
        // [v0 >= 3] (undetermined after the widen). The crash was ingesting that undetermined atom.
        val reason = assertNotNull(s.atomAntecedentsDerived(eqId))
        assertEquals(carveReason.toList(), reason.toList(), "hole eq atom must cite its carve reason")
        assertReasonsCiteOnlyDeterminedAtoms(s, "after backtrack")

        // The reconstructed level is the carve level (2), not the widened-away bound level (3).
        assertEquals(2, s.atomLevelForConflict(eqId))
    }
}
