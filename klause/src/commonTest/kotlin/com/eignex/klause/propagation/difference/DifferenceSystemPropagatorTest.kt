package com.eignex.klause.propagation.difference

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.solver.differenceFragmentOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DifferenceSystemPropagatorTest {
    private fun row(aux: Int, hi: Int, lo: Int, bound: Long) =
        ReifiedLinear(aux, longArrayOf(1, -1), intArrayOf(hi, lo), LinearOp.LE, bound)

    private fun problemOf(
        numBools: Int,
        numInts: Int,
        rows: List<ReifiedLinear>,
        domains: Array<IntDomain> = Array(numInts) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
    ): Problem {
        val bounds = IntBounds.fromFiniteBounds(
            LongArray(numInts) { domains[it].min },
            LongArray(numInts) { domains[it].max },
            BooleanArray(numInts) { domains[it].min == Long.MIN_VALUE },
            BooleanArray(numInts) { domains[it].max == Long.MAX_VALUE },
            null,
            null,
        )
        val fragment = assertNotNull(differenceFragmentOf(Array<Factor>(rows.size) { rows[it] }, numInts, bounds))
        val factors = ArrayList<Factor>(rows)
        factors.add(DifferenceSystem(fragment.edges))
        return Problem(
            numBoolVars = numBools,
            numIntVars = numInts,
            intDomains = domains,
            factors = factors.toTypedArray(),
        )
    }

    private fun boxed(numInts: Int, hi: Long) = Array(numInts) { IntDomain(0, hi) }

    private fun systemId(problem: Problem) = problem.factors.size - 1

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
    fun `a sweep should visit distinct heads before its budget expires`() {
        val rows = (0..64).map { row(it, (it + 1) % 65, it, 0L) } +
            (1..64).map { row(64 + it, it, 0, -1L) }
        val problem = problemOf(numBools = 129, numInts = 65, rows = rows)
        val state = stateOf(problem)
        for (aux in 0..64) assertTrue(state.pinLit(Lit.make(aux, true)))

        assertTrue(runSystem(problem, state))

        assertEquals(false, state.boolValues[65])
        assertEquals((1..64).map { Lit.make(it, false) }.toSet(), state.boolAntecedents[65]?.toSet())
    }

    @Test
    fun `closed heads should not consume the refutation budget`() {
        val rows = (0..64).map { row(it, (it + 1) % 65, it, 0L) } + row(65, 64, 0, -1L)
        val problem = problemOf(numBools = 66, numInts = 65, rows = rows)
        val state = stateOf(problem)
        for (aux in 0..64) assertTrue(state.pinLit(Lit.make(aux, true)))

        assertTrue(runSystem(problem, state))

        assertEquals(false, state.boolValues[65])
        assertEquals(listOf(Lit.make(64, false)), state.boolAntecedents[65]?.toList())
    }

    @Test
    fun `a deferred head should be visited after a decision without new graph assertions`() {
        val rows = (1..64).map { row(it - 1, it, 0, 0L) } + row(64, 0, 64, -1L)
        val problem = problemOf(numBools = 65, numInts = 65, rows = rows, domains = boxed(65, 2L))
        val state = stateOf(problem, Lit.make(64, true))
        state.levelToDecisionVar.add(64)
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[63])
        state.currentLevel = 2
        state.levelToDecisionVar.add(problem.numBoolVars + 2)
        assertTrue(state.tightenIntMin(2, 1L))

        assertTrue(runSystem(problem, state))

        assertEquals(false, state.boolValues[63])
        assertEquals(listOf(Lit.make(64, false)), state.boolAntecedents[63]?.toList())
    }

    @Test
    fun `a deferred head should survive an assertion in an unrelated component`() {
        val rows = (0..64).map { row(it, 65, it, if (it == 64) 0L else 2L) } +
            row(65, 0, 65, -1L) + (1..64).map { row(65 + it, it, 0, 0L) } + row(130, 66, 67, 0L)
        val problem = problemOf(numBools = 131, numInts = 68, rows = rows)
        val state = stateOf(problem)
        for (aux in 0..65) assertTrue(state.pinLit(Lit.make(aux, true)))
        state.levelToDecisionVar.add(0)
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[129])
        state.currentLevel = 2
        state.levelToDecisionVar.add(130)
        assertTrue(state.pinLit(Lit.make(130, true)))

        assertTrue(runSystem(problem, state))

        assertEquals(false, state.boolValues[129])
        assertEquals(setOf(Lit.make(64, false), Lit.make(65, false)), state.boolAntecedents[129]?.toSet())
    }

    @Test
    fun `a sibling should revisit an implication released by rollback at the same depth`() {
        val problem = problemOf(
            numBools = 4,
            numInts = 5,
            rows = listOf(row(0, 1, 0, -1L), row(1, 2, 1, -1L), row(2, 0, 2, -1L), row(3, 3, 4, 0L)),
        )
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        state.levelToDecisionVar.add(0)
        val root = state.mark()
        state.currentLevel = 2
        state.levelToDecisionVar.add(3)
        assertTrue(state.pinLit(Lit.make(3, true)))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[2])
        state.undoTo(root)
        assertEquals(null, state.boolValues[2])
        state.currentLevel = 2
        state.levelToDecisionVar.add(3)
        assertTrue(state.pinLit(Lit.make(3, false)))

        assertTrue(runSystem(problem, state))

        assertEquals(false, state.boolValues[2])
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false)), state.boolAntecedents[2]?.toSet())
    }

    @Test
    fun `two asserted rows should refute the third citing only the rows that forced it`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[2])
        val antecedents = assertNotNull(state.boolAntecedents[2])
        assertEquals(
            setOf(Lit.make(0, false), Lit.make(1, false)),
            antecedents.toSet(),
            "exactly the guards on the refuting path",
        )
    }

    @Test
    fun `a fully asserted negative cycle should conflict naming every row on the cycle`() {
        val problem = triangle()
        val state = stateOf(problem, Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))
        assertFalse(runSystem(problem, state))
        val id = systemId(problem)
        val reason = assertNotNull(problem.propagators[id].conflictReason(state, id))
        assertEquals(setOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false)), reason.toSet())
    }

    @Test
    fun `a false aux should assert the row's negation`() {
        // `¬(x1 − x0 ≤ −5)` is `x1 − x0 ≥ −4`, which the second row's `x1 − x0 ≤ −9` contradicts.
        val problem = problemOf(numBools = 2, numInts = 2, rows = listOf(row(0, 1, 0, -5L), row(1, 1, 0, -9L)))
        val state = stateOf(problem, Lit.make(0, false))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[1])
    }

    @Test
    fun `a range at the unbounded-search clamp should refute nothing`() {
        // The two sides sum past Long. A wrapped sum reads as a hugely negative distance, which would
        // refute every row in the model — the false-UNSAT shape this fold has to refuse outright.
        val clamp = 1L shl 62
        val problem = problemOf(
            numBools = 1,
            numInts = 2,
            rows = listOf(row(0, 1, 0, -6L)),
            domains = Array(2) { IntDomain(-clamp, clamp) },
        )
        val state = stateOf(problem)
        assertTrue(runSystem(problem, state))
        assertEquals(null, state.boolValues[0])
    }

    @Test
    fun `a route through a model row to the constant node should refute citing both segments`() {
        val rows = listOf(
            ReifiedLinear(1, longArrayOf(1, -1), intArrayOf(2, 0), LinearOp.LE, 0L), // aux1: a - y <= 0
            ReifiedLinear(2, longArrayOf(1, -1), intArrayOf(1, 3), LinearOp.LE, 0L), // aux2: x - b <= 0
            row(0, 0, 1, 4L), // aux0: y - x <= 4
        )
        val domains = arrayOf(
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), // y, open
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE), // x, open
            IntDomain(10, 10), // a
            IntDomain(5, 5), // b
        )
        val problem = problemOf(numBools = 3, numInts = 4, rows = rows, domains = domains)
        val state = stateOf(problem, Lit.make(1, true), Lit.make(2, true))
        assertTrue(runSystem(problem, state))
        assertEquals(false, state.boolValues[0])
        val antecedents = assertNotNull(state.boolAntecedents[0])
        assertEquals(
            setOf(Lit.make(1, false), Lit.make(2, false)),
            antecedents.toSet(),
            "both segments of the route are guarded rows and both must be named",
        )
    }

    @Test
    fun `two states sharing one propagator should keep separate systems`() {
        val problem = triangle()
        val id = systemId(problem)
        val decided = stateOf(problem, Lit.make(0, true), Lit.make(1, true))
        assertTrue(problem.propagators[id].propagate(decided, id))
        val fresh = stateOf(problem, Lit.make(0, true))
        assertTrue(problem.propagators[id].propagate(fresh, id))
        assertEquals(null, fresh.boolValues[2])
    }
}
