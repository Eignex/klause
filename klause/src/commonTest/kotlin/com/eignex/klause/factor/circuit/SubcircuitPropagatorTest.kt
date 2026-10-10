package com.eignex.klause.factor.circuit

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult.Implied
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.undoTo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubcircuitPropagatorTest {

    @Test
    fun `subcircuit deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x5EA5)
        repeat(300) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, n - 1L) },
                factors = arrayOf<Factor>(Circuit(succ = IntArray(n) { it }, subcircuit = true)),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "subcircuit#$iter") { state ->
                (0 until 6).all {
                    val v = rng.nextInt(n)
                    state.excludeIntValue(v, rng.nextInt(n).toLong())
                }
            }
        }
    }

    @Test
    fun `a premature subtour cites only its edges and one node that must join it`() {
        // succ(0)=1 and succ(1)=0 close a 2-cycle while node 2 cannot opt out (2 is outside succ(2)'s
        // domain), so the cycle is too short. Node 3 is tightened but may still opt out: a reason
        // citing every successor would name it.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3, 4), subcircuit = true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 1))
        check(state.tightenIntMax(0, 1))
        check(state.tightenIntMin(1, 0))
        check(state.tightenIntMax(1, 0))
        check(state.tightenIntMax(2, 1))
        check(state.tightenIntMin(3, 1))
        state.currentFactor = 0

        assertFalse(state.factorAt(0).propagate(state, 0))

        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val cited = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertEquals(setOf(0, 1, 2), cited)
        ConflictReasonOracle.assertEntailed(problem, state, 0, "subcircuit-subtour")
    }

    @Test
    fun `a mandatory node the root cannot reach cites the arcs out of the reached set`() {
        // Nodes 0 and 3 cannot opt out. Node 0 reaches only {0, 1, 2}, whose successors all stay at or below 2.
        val problem = problem(4)
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 1))
        check(state.tightenIntMax(0, 2))
        check(state.tightenIntMax(1, 2))
        check(state.tightenIntMax(2, 2))
        check(state.tightenIntMax(3, 2))
        state.currentFactor = 0

        assertFalse(state.factorAt(0).propagate(state, 0))

        val expected = setOf(
            Lit.make(state.atomVarGe(0, 1), false),
            Lit.make(state.atomVarLe(0, 2), false),
            Lit.make(state.atomVarLe(1, 2), false),
            Lit.make(state.atomVarLe(2, 2), false),
            Lit.make(state.atomVarLe(3, 2), false),
        )
        assertEquals(expected, state.factorAt(0).conflictReason(state, 0)?.toSet())
        ConflictReasonOracle.assertEntailed(problem, state, 0, "subcircuit-unreached")
    }

    private fun problem(n: Int, lo: Int = 0, hi: Int = n - 1): Problem {
        val factor = Circuit(succ = IntArray(n) { it }, subcircuit = true)
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(lo.toLong(), hi.toLong()) },
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `propagation rejects a claimed successor taken by two included nodes`() {
        // succ[0]=2 and succ[1]=2 — two nodes claim the same successor. Infeasible.
        val problem = problem(4)
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 1 to 2)))
        assertTrue(result is Unsat, "two nodes claiming same successor should be Unsat; got $result")
    }

    @Test
    fun `propagation forces closing edge when only one valid target remains`() {
        // Fixed: succ[0]=1, succ[1]=2. Node 2 must close back to 0 (others claimed or excluded).
        // Domain for succ[2] includes 0, 1, 2 — after propagation succ[2]=0 should be forced.
        val factor = Circuit(succ = intArrayOf(0, 1, 2), subcircuit = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 2)))
        assertTrue(result is Implied, "chain 0→1→2 must propagate; got $result")
        assertEquals(0, result.ints[2], "succ[2] must be forced to 0 to close the cycle; got ${result.ints}")
    }


    @Test
    fun `shared subcircuit connectivity work preserves a peer cut reason across undo`() {
        val projection = PropagationProblem(problem(4))
        val first = PropagationState(projection, Assumptions.None)
        val second = PropagationState(projection, Assumptions.None)
        first.undoLogging = true
        second.undoLogging = true
        val root = first.mark()
        first.currentLevel = 1
        check(first.tightenIntMin(0, 1))
        for (v in 0..3) check(first.tightenIntMax(v, 2))
        assertNotNull(first.runToFixpoint(allFactors = true))
        val reason = assertNotNull(first.factorAt(0).conflictReason(first, 0)).copyOf()
        second.currentLevel = 1
        for (v in 0..3) check(second.excludeIntValue(v, v.toLong()))

        assertNull(second.runToFixpoint(allFactors = true))
        assertContentEquals(reason, first.factorAt(0).conflictReason(first, 0))
        ConflictReasonOracle.assertEntailed(projection.problem, first, 0)
        first.undoTo(root)
        first.currentLevel = 1
        for (v in 0..3) check(first.excludeIntValue(v, v.toLong()))
        assertNull(first.runToFixpoint(allFactors = true))
    }
}
