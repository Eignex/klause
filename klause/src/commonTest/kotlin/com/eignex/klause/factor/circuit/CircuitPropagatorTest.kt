package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationResult.Implied
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.boundEstablishment
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitPropagatorTest {

    @Test
    fun `strong connectivity should reject a candidate graph with an inescapable component`() {
        // No edge is fixed, so only strong connectivity sees that {3, 4, 5} never leads back.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 3),
                IntDomain(0, 2),
                IntDomain(0, 1),
                IntDomain(4, 5),
                IntDomain(3, 5),
                IntDomain(3, 4),
            ),
            factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3, 4, 5))),
        )
        assertTrue(problem.propagate() is Unsat)
    }

    @Test
    fun `subtour conflict reason should be a sound nogood citing only the subtour edges`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 2), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 1))
        check(state.tightenIntMax(0, 1))
        check(state.tightenIntMin(1, 0))
        check(state.tightenIntMax(1, 0))
        check(state.tightenIntMax(2, 2))
        state.currentFactor = 0
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it == 0 || it == 1 }, "$citedVars")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "circuit-subtour")
    }

    @Test
    fun `circuit deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x5EA5)
        repeat(60) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, n - 1L) },
                factors = arrayOf<Factor>(Circuit(succ = IntArray(n) { it })),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "circuit#$iter") { state ->
                (0 until 6).all {
                    val v = rng.nextInt(n)
                    state.excludeIntValue(v, rng.nextInt(n).toLong())
                }
            }
        }
    }

    @Test
    fun `a chain should block its own closing citing only its fixed edges`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(Circuit(succ = IntArray(5) { it })),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 1) && state.tightenIntMax(0, 1))
        check(state.tightenIntMin(1, 2) && state.tightenIntMax(1, 2))
        check(state.excludeIntValue(4, 3))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.boundEstablishment(2, 1, lower = true)!!.reason)!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.EQ, 1L), Triple(1, AtomKind.EQ, 2L)), cited.toSet())
    }

    private fun fourNodeSubcircuitProblem(): Problem {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3), subcircuit = true)
        return Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `propagation should reject a successor pointing to a pinned-excluded node`() {
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 2 to 2)))
        assertTrue(result is Unsat, "$result")
    }

    @Test
    fun `propagation should reject a premature closed sub-cycle that strands an included node`() {
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 0, 2 to 3)))
        assertTrue(result is Unsat, "$result")
    }

    @Test
    fun `propagation should forbid closing a chain that would strand included nodes`() {
        // Pigeonhole removes 1 and 3 from succ(1); only the chain walk removes 0.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 2 to 3)))
        assertTrue(result is Implied, "$result")
        assertEquals(2, result.intValueOrNull(1))
    }
}
