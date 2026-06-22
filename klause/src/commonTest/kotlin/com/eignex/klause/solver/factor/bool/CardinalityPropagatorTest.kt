package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CardinalityPropagatorTest {

    @Test
    fun `at-least boundary forces remaining unassigned to true`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(4) { Lit.make(it, true) }, min = 2, max = 4)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        assertIs<PropagationResult.Implied>(session.pinBool(1, false))
        assertEquals(true, session.boolValue(2))
        assertEquals(true, session.boolValue(3))
    }

    @Test
    fun `at-most boundary forces remaining to false when max reached`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(4) { Lit.make(it, true) }, min = 0, max = 1)),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, true))
        assertEquals(false, session.boolValue(1))
        assertEquals(false, session.boolValue(2))
        assertEquals(false, session.boolValue(3))
    }

    @Test
    fun `max zero forces all literals false at bake time`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 0, max = 0)),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.baked)
        for (v in 0..2) assertEquals(false, impl.bools[v], "var $v should be forced false with max=0")
    }

    @Test
    fun `enumerate matches brute force for at-least 2 of 4`() {
        for (seed in 1L..3L) {
            val problem = Problem(
                numBoolVars = 4,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(Cardinality(IntArray(4) { Lit.make(it, true) }, min = 2, max = 4)),
            )
            val brute = (0 until 16)
                .filter { mask -> (0..3).count { (mask shr it) and 1 == 1 } >= 2 }
                .map { mask -> (0..3).map { (mask shr it) and 1 == 1 } }
                .toHashSet()
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100).map { it.bools.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: at-least-2-of-4 must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for exactly 1 of 3`() {
        for (seed in 1L..3L) {
            val problem = Problem(
                numBoolVars = 3,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 1, max = 1)),
            )
            val brute = (0 until 8)
                .filter { mask -> (0..2).count { (mask shr it) and 1 == 1 } == 1 }
                .map { mask -> (0..2).map { (mask shr it) and 1 == 1 } }
                .toHashSet()
            val found = BacktrackSolver(problem)
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100).map { it.bools.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: exactly-1-of-3 must match brute force")
        }
    }
}
