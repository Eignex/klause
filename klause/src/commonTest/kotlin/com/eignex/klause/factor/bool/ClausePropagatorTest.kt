package com.eignex.klause.factor.bool

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClausePropagatorTest {

    @Test
    fun `unit clause forces its literal at bake time`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.baked)
        assertEquals(true, impl.bools[0])
    }

    @Test
    fun `negative unit clause forces var false at bake time`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false)))),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.baked)
        assertEquals(false, impl.bools[0])
    }

    @Test
    fun `two of three false forces last to true`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val session = PropagationSession(problem)
        assertIs<PropagationResult.Implied>(session.pinBool(0, false))
        assertIs<PropagationResult.Implied>(session.pinBool(1, false))
        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `all literals false yields conflict`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            ),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false)))
        assertIs<PropagationResult.Unsat>(r)
    }

    @Test
    fun `enumerate matches brute force for 3-literal clause`() {
        for (seed in 1L..4L) {
            val problem = Problem(
                numBoolVars = 3,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
                ),
            )
            val brute = (1 until 8)
                .map { mask -> (0..2).map { (mask shr it) and 1 == 1 } }
                .toHashSet()
            val found = BacktrackSolver(problem.bake())
                .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
                .take(100).map { it.bools.toList() }.toHashSet()
            assertEquals(brute, found, "seed=$seed: 3-literal clause must match brute force")
        }
    }
}
