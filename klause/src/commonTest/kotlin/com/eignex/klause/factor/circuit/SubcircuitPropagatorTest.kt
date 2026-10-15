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
    fun `subcircuit deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x5EA5)
        repeat(40) { iter ->
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
    fun `a premature subtour should cite only its edges and one node that must join it`() {
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
    fun `a mandatory node the root cannot reach should cite the arcs out of the reached set`() {
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
    fun `propagation should reject a successor claimed by two included nodes`() {
        val problem = problem(4)
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 1 to 2)))
        assertTrue(result is Unsat, "$result")
    }

    @Test
    fun `propagation should force the closing edge when only one valid target remains`() {
        val factor = Circuit(succ = intArrayOf(0, 1, 2), subcircuit = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 2)))
        assertTrue(result is Implied, "$result")
        assertEquals(0, result.ints[2], "${result.ints}")
    }

    @Test
    fun `shared subcircuit connectivity work should preserve a peer cut reason across undo`() {
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
