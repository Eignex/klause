package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementTest {

    /**
     * Pinned-index channel over a var array: `result = arr[idx]` with `idx` pinned copies
     * bounds both ways between `result` and the selected element. The copied bound is the
     * other var's search-derived state, so the recorded reason must cite that var — a
     * reason carrying only the index pin records `idx = pos → bound` as if it held for
     * every value of the other var, and conflict analysis resolving through it learns a
     * clause that prunes feasible assignments (surfaced as a false UNSAT on
     * project-planning). The element→result direction is also covered by the union bound,
     * which cites the array; the result→element direction below is served by the channel
     * alone.
     */
    @Test
    fun `pinned index channel cites the result bound when lifting the element`() {
        // ints: idx(0) root-pinned to 0, result(1), element(2); result = [element][idx].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = intArrayOf(2), arrIsVars = true, indexOffset = 0),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(1, 1)) { "tighten result min failed" }
        check(problem.factors[0].propagate(state, 0)) { "element propagate failed" }
        check(state.intDomains[2].min == 1) { "channel must lift the element's min to 1" }

        val ant = state.intMinAntecedents[2]
        assertNotNull(ant, "the channeled bound is search-derived; its reason must not be a leaf")
        val citesResult = ant.any { lit ->
            val v = Lit.variable(lit)
            v >= problem.numBoolVars && state.atomIntVar[v - problem.numBoolVars] == 1
        }
        assertTrue(citesResult, "reason must cite the result var's bound; got ${ant.toList()}")
    }
}
