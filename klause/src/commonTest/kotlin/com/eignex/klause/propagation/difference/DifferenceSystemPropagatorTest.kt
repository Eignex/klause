package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The joint difference system as a propagator. Every model here leaves the integer variables unbounded,
 * so a row-at-a-time propagator can deduce nothing from them at all: what the tests pin down is exactly
 * the reasoning the graph adds — refuting a reified row from a path the asserted rows already carry, and
 * refusing a set of rows whose cycle is negative.
 */
class DifferenceSystemPropagatorTest {

    /** `aux ↔ (hi − lo ≤ bound)`, over unbounded integers. */
    private fun row(aux: Int, hi: Int, lo: Int, bound: Long) =
        ReifiedLinear(aux, longArrayOf(1, -1), intArrayOf(hi, lo), LinearOp.LE, bound)

    private fun problemOf(numBools: Int, numInts: Int, rows: List<ReifiedLinear>): Problem {
        val domains = Array(numInts) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) }
        val fragment = assertNotNull(differenceFragmentOf(Array<Factor>(rows.size) { rows[it] }, numInts, domains))
        val factors = ArrayList<Factor>(rows)
        factors.add(DifferenceSystem(fragment.edges))
        return Problem(
            numBoolVars = numBools,
            numIntVars = numInts,
            intDomains = domains,
            factors = factors.toTypedArray(),
        )
    }

    /** The index of the system [problemOf] appends last. */
    private fun systemId(problem: Problem) = problem.factors.size - 1

    /** A three-row cycle `x0 < x1 < x2 < x0`, each row reified on its own aux. */
    private fun triangle() = problemOf(
        numBools = 3,
        numInts = 3,
        rows = listOf(row(0, 1, 0, -1L), row(1, 2, 1, -1L), row(2, 0, 2, -1L)),
    )

    private fun stateOf(problem: Problem, vararg pins: Int): PropagationState {
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        for (lit in pins) check(state.pinLit(lit)) { "pinning literal $lit failed" }
        return state
    }

    private fun runSystem(problem: Problem, state: PropagationState): Boolean {
        val id = systemId(problem)
        return problem.propagators[id].propagate(state, id)
    }

    @Test
    fun `two asserted rows refute the third before it is decided`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[2], "closing the cycle is refuted ahead of the decision")
    }

    @Test
    fun `a refutation cites the rows that forced it`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        runSystem(problem, state)
        val antecedents = assertNotNull(state.boolAntecedents[2], "the pin must carry its forcing clause")
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false)),
            antecedents.toSet(),
            "exactly the guards on the refuting path",
        )
    }

    @Test
    fun `a row whose cycle is not closed is left open`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true))
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[2], "one row alone implies nothing about the third")
    }

    @Test
    fun `a positive cycle refutes nothing`() {
        val problem = problemOf(
            numBools = 3,
            numInts = 3,
            rows = listOf(row(0, 1, 0, 1L), row(1, 2, 1, 1L), row(2, 0, 2, 1L)),
        )
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[2], "the cycle sums to 3 and is satisfiable")
    }

    @Test
    fun `a fully asserted negative cycle is a conflict`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        assertFalse(runSystem(problem, state))
    }

    @Test
    fun `the conflict names the rows on the cycle`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        runSystem(problem, state)
        val id = systemId(problem)
        val reason = assertNotNull(problem.propagators[id].conflictReason(state, id))
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)), reason.toSet())
    }

    @Test
    fun `a false aux asserts the row's negation`() {
        // `¬(x1 − x0 ≤ −5)` is `x1 − x0 ≥ −4`, which the second row's `x1 − x0 ≤ −9` contradicts.
        val problem = problemOf(numBools = 2, numInts = 2, rows = listOf(row(0, 1, 0, -5L), row(1, 1, 0, -9L)))
        val state = stateOf(problem, Lit.make(0, false))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[1], "the false branch of a reified difference constrains too")
    }

    @Test
    fun `two states sharing one propagator keep separate systems`() {
        // One propagator instance backs every arm of a portfolio, so its graph must not be shared.
        val problem = triangle()
        val id = systemId(problem)
        val decided = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(problem.propagators[id].propagate(decided, id))
        val fresh = stateOf(problem, Lit.make(0, true))
        assertTrue(problem.propagators[id].propagate(fresh, id))
        assertEquals(null, fresh.boolValues[2], "the second state must not inherit the first's edges")
    }
}
