package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SetDomain
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetInTest {

    @Test
    fun `forced-true SetIn drives LS to include the element`() {
        // One set var with universe {0,1,2,3}, forced to include element 1 and exclude element 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            numSetVars = 1,
            setDomains = arrayOf(SetDomain.unrestricted(4)),
            factors = listOf(
                SetIn(setVar = 0, element = 1, forced = true),
                SetIn(setVar = 0, element = 2, forced = false),
            ),
        )
        val r = LocalSearchSolver(problem).solve(LocalSearchParams(randomSeed = 7L, maxFlips = 1_000))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.sets.size)
        val members = sat.assignment.sets[0].toList()
        assertTrue(1 in members, "element 1 should be in the set: $members")
        assertTrue(2 !in members, "element 2 should NOT be in the set: $members")
    }

    @Test
    fun `reified SetIn channels aux bool to set membership`() {
        // Two reified SetIn over the same set, plus a Clause forcing both auxes true.
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            numSetVars = 1,
            setDomains = arrayOf(SetDomain.unrestricted(3)),
            factors = listOf(
                SetIn(setVar = 0, element = 0, auxBoolVar = 0),
                SetIn(setVar = 0, element = 2, auxBoolVar = 1),
                Clause(intArrayOf(com.eignex.klause.solver.Lit.make(0, true))),
                Clause(intArrayOf(com.eignex.klause.solver.Lit.make(1, true))),
            ),
        )
        val r = LocalSearchSolver(problem).solve(LocalSearchParams(randomSeed = 11L, maxFlips = 2_000))
        val sat = assertIs<SolveResult.Sat>(r)
        assertTrue(sat.assignment.bools[0])
        assertTrue(sat.assignment.bools[1])
        val members = sat.assignment.sets[0].toList()
        assertTrue(0 in members)
        assertTrue(2 in members)
    }

    @Test
    fun `SetIn propagates element-into-required when aux is pinned true`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            numSetVars = 1,
            setDomains = arrayOf(SetDomain.unrestricted(3)),
            factors = listOf(
                SetIn(setVar = 0, element = 1, auxBoolVar = 0),
                Clause(intArrayOf(com.eignex.klause.solver.Lit.make(0, true))),
            ),
        )
        // After bake propagation, element 1 should be required in setDomain 0.
        val state = com.eignex.klause.solver.propagation.PropagationState(
            problem,
            com.eignex.klause.solver.Assumptions.None,
        )
        // Bake-time propagation already ran via Problem.baked; re-run for the assertion.
        state.runToFixpoint(allFactors = true)
        assertTrue(state.setRequired[0].get(1), "element 1 should be required after propagation")
    }
}
