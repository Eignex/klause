package com.eignex.klause.propagation

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The reason-graph ordering contract 1UIP resolution rests on: a reason may cite only facts established
 * before the fact it explains. An order literal materialised after its bound had already crossed carries no
 * trail slot, so its reason is derived from the *live* endpoint — which is the establishing move only while
 * the endpoint still sits on the literal's threshold. Once a later move overshoots the threshold, the live
 * endpoint postdates the literal and explaining it that way puts a back edge in the reason graph, letting a
 * premise resolved out earlier recur and lose its literal from the nogood.
 */
class AtomsOvershotBoundReasonTest {

    private fun freshState(numVars: Int, hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 1,
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

    @Test
    fun `a bound literal at the live endpoint cites the move that established it`() {
        val s = freshState(numVars = 2, hi = 9)
        val premise = intArrayOf(Lit.make(0, false))

        s.beginLevel(0, fid = 0)
        s.tightenIntMin(0, 2, premise)
        // Materialised only now, after the crossing: no trail slot, so the reason is derived.
        val atomId = s.atomVarGe(0, 2) - s.problem.numBoolVars

        assertEquals(premise.toList(), s.atomAntecedentsDerived(atomId)?.toList())
    }

    @Test
    fun `a bound literal the endpoint has overshot gets no reason`() {
        val s = freshState(numVars = 2, hi = 9)

        s.beginLevel(0, fid = 0)
        s.tightenIntMin(0, 2, intArrayOf(Lit.make(0, false)))
        val atomId = s.atomVarGe(0, 2) - s.problem.numBoolVars

        // A second move raises the min past the threshold. It never crosses 2, so the literal keeps no
        // trail slot, and the live endpoint is no longer the move that established it.
        s.beginLevel(1, fid = 1)
        s.tightenIntMin(0, 5, intArrayOf(Lit.make(s.atomVarLe(1, 4), false)))

        assertEquals(true, s.atomCurrentTruth(atomId))
        assertNull(s.atomAntecedentsDerived(atomId))
    }
}
