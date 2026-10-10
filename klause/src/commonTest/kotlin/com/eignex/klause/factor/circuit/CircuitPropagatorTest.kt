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
    fun `strong connectivity rejects a candidate graph with an inescapable component`() {
        // Nodes {3,4,5} only point within themselves, so once the cycle enters that block (via 0→3)
        // it can never return — no Hamiltonian circuit exists. No edge is fixed, so the subtour /
        // chain checks see nothing; only the strong-connectivity (SCC) condition rules it out.
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
        assertTrue(problem.propagate() is Unsat, "an inescapable component must be rejected by SCC reasoning")
    }

    @Test
    fun `subtour conflict reason is a sound nogood citing only the subtour edges`() {
        // Globally satisfiable (4-node Hamiltonian cycles exist). A decision fixes succ[0]=1 and
        // succ[1]=0, a premature 2-cycle. The sharp reason must cite only those two successor vars,
        // not the idle var 2 (tightened but uninvolved), and must be entailed by the circuit.
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
        check(state.tightenIntMax(0, 1)) // succ[0]=1
        check(state.tightenIntMin(1, 0))
        check(state.tightenIntMax(1, 0)) // succ[1]=0
        check(state.tightenIntMax(2, 2)) // idle var 2 tightened → a coarse reason would cite it
        state.currentFactor = 0
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it == 0 || it == 1 }, "reason must cite only the subtour edges, got $citedVars")
        assertTrue(2 !in citedVars, "idle successor var 2 must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "circuit-subtour")
    }

    @Test
    fun `circuit deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x5EA5)
        repeat(300) { iter ->
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
    fun `a chain blocks its own closing citing only its fixed edges`() {
        // 0 -> 1 -> 2 is fixed, so 2 cannot return to 0 short of all five nodes; node 4's hole at 3 plays no part.
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
    fun `propagation rejects a successor pointing to a pinned-excluded node`() {
        // succ[0] = 2 but node 2 is pinned excluded (succ[2] = 2). An excluded node has no
        // predecessor in the cycle, so this is a propagated conflict.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 2 to 2)))
        assertTrue(result is Unsat, "pointing at a pinned-excluded node should be Unsat; got $result")
    }

    @Test
    fun `propagation rejects a premature closed sub-cycle that strands an included node`() {
        // 0↔1 is a closed 2-cycle of fixed edges; node 2 is pinned to a non-self successor so
        // it must be on the cycle, but the cycle is already sealed — infeasible.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 0, 2 to 3)))
        assertTrue(result is Unsat, "a sealed sub-cycle leaving an included node out should be Unsat; got $result")
    }

    @Test
    fun `propagation forbids closing a chain that would strand included nodes`() {
        // Chain-walk: fixed edges 0→1 and 2→3 make nodes 0 and 2 included. For succ[1],
        // pigeonhole removes 1 (claimed by node 0) and 3 (claimed by node 2), self-looping to 1 is
        // already excluded, and the chain-walk forbids 0 (closing the {0,1} sub-cycle would strand
        // the other included nodes) — leaving 2 as the only option, so succ[1] is forced to 2.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 2 to 3)))
        assertTrue(result is Implied, "feasible chain config should propagate, not fail; got $result")
        assertEquals(2, result.intValueOrNull(1), "succ[1] must be forced to 2 (0 closes a sub-cycle, 1/3 taken)")
    }

}
