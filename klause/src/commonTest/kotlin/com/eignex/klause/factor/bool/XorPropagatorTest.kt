package com.eignex.klause.factor.bool

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class XorPropagatorTest {

    @Test
    fun `single unassigned var forced true to reach odd target`() {
        // v0=true, v1=true -> pinnedParity=0; target=1 -> v2 must be true
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        assertIs<PropagationResult.Implied>(session.pinBool(1, true))
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `single unassigned var forced false when parity already met`() {
        // v0=true -> parity=1 = target=1; v1 must be false to keep parity at 1
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(2) { Lit.make(it, true) }, targetParity = 1)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        assertEquals(false, session.boolValue(1))
    }

    @Test
    fun `all vars assigned with wrong parity yields conflict`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `enumerate matches brute force for 3-var xor odd target`() {
        for (seed in 1L..4L) {
            val problem = Problem(
                numBoolVars = 3,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(Xor(IntArray(3) { Lit.make(it, true) }, targetParity = 1)),
            )
            val brute = (0 until 8)
                .filter { mask -> (0..2).count { (mask shr it) and 1 == 1 } % 2 == 1 }
                .map { mask -> (0..2).map { (mask shr it) and 1 == 1 } }
                .toHashSet()
            val found = BacktrackSolver(problem.bake())
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100).map { it.bools.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: 3-var xor odd target must match brute force")
        }
    }
}
