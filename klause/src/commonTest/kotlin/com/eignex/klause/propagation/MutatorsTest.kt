package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MutatorsTest {

    private fun freshState(hi: Int): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, hi.toLong())),
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    @Test
    fun `an equality decision is its eq literal and explains both bounds by it`() {
        val s = freshState(hi = 5)
        val ge = s.atomVarGe(0, 3)
        val le = s.atomVarLe(0, 3)

        check(s.setIntAsDecision(0, 3))

        val eq = s.atomVarEq(0, 3)
        val byEq = listOf(Lit.make(eq, false))
        assertNull(s.atomAntecedentsDerived(eq - s.problem.numBoolVars))
        assertEquals(1, s.atomLevelForConflict(eq - s.problem.numBoolVars))
        assertEquals(byEq, s.atomAntecedentsDerived(ge - s.problem.numBoolVars)?.toList())
        assertEquals(byEq, s.atomAntecedentsDerived(le - s.problem.numBoolVars)?.toList())
    }

    @Test
    fun `a bound decision that snaps past a hole is the only unexplained literal at its level`() {
        // 3 is carved at level 1, so deciding v >= 3 at level 2 lands the bound on 4. The landed bound must rest on
        // the decision and the hole, and the decision on nothing; were they to cite each other, no literal at the
        // level would be left for 1UIP to stop at.
        val s = freshState(hi = 5)
        s.levelToDecisionVar.add(s.problem.numBoolVars)
        s.currentLevel = s.levelToDecisionVar.size
        check(s.excludeIntValue(0, 3, null))

        check(s.setIntMinAsDecision(0, 3))

        val decided = s.atomVarGe(0, 3)
        val landed = s.atomVarGe(0, 4)
        assertEquals(4L, s.intDomains[0].min)
        assertNull(s.atomAntecedentsDerived(decided - s.problem.numBoolVars))
        assertEquals(
            setOf(Lit.make(decided, false), Lit.make(s.atomVarEq(0, 3), true)),
            s.atomAntecedentsDerived(landed - s.problem.numBoolVars)?.toSet(),
        )
    }

    @Test
    fun `a value a bound decision sweeps past cites the decided bound`() {
        val s = freshState(hi = 5)
        val eq = s.atomVarEq(0, 2)

        check(s.setIntMinAsDecision(0, 3))

        val decided = s.atomVarGe(0, 3)
        assertEquals(
            listOf(Lit.make(decided, false)),
            s.atomAntecedentsDerived(eq - s.problem.numBoolVars)?.toList(),
        )
    }
}
