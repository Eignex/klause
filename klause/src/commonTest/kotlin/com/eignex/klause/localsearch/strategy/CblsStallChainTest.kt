package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.proposeRepairChains
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CblsStallChainTest {

    private fun chainProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2),
        ),
    )

    private fun stateAt(
        problem: Problem,
        vals: IntArray,
        assumptions: Assumptions = Assumptions.None,
    ): LocalSearchState {
        val state = LocalSearchState(problem.bake(), Random(7), assumptions)
        for (i in vals.indices) state.assignment.setInt(i, vals[i].toLong())
        state.recompute()
        return state
    }

    @Test
    fun `builder grows the directed two-step chain and leaves the state untouched`() {
        val state = stateAt(chainProblem(), intArrayOf(0, 2))
        assertEquals(2L, state.cost, "fixture must start at cost 2 (F0 degree)")
        val costBefore = state.cost
        val stepBefore = state.step
        val sink = MoveSink()

        val emitted = state.proposeRepairChains(seedFactor = 0, maxDepth = 4, firstMoveCap = 4, sink = sink)

        assertTrue(emitted >= 1, "the F0 repair must seed at least one chain (got $emitted)")
        val expected = listOf(Move.IntSet(0, 2), Move.IntSet(1, 0))
        val chain = sink.list.filterIsInstance<Move.Compound>().firstOrNull { it.parts == expected }
        assertTrue(chain != null, "the directed repair chain $expected must be emitted (got ${sink.list})")
        assertEquals(costBefore, state.cost, "cost must be restored")
        assertEquals(stepBefore, state.step, "step must be restored")
        assertEquals(0, state.assignment.intValue(0), "x0 must be restored")
        assertEquals(2, state.assignment.intValue(1), "x1 must be restored")
        state.apply(chain)
        assertEquals(0L, state.cost, "the chain must solve the fixture")
    }

    @Test
    fun `chains never touch frozen vars`() {
        val state = stateAt(chainProblem(), intArrayOf(0, 2), Assumptions(ints = mapOf(1 to 2)))
        val sink = MoveSink(Assumptions(ints = mapOf(1 to 2)))
        state.proposeRepairChains(seedFactor = 0, maxDepth = 4, firstMoveCap = 4, sink = sink)
        for (m in sink.list) {
            if (m is Move.Compound) {
                for (p in m.parts) {
                    assertTrue((p as Move.IntSet).varId != 1, "chain must not touch a frozen var")
                }
            }
        }
    }

    @Test
    fun `default stallChainCap 0 never emits a chain compound`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(7))
        state.recompute()
        val strategy = Cbls()
        var compounds = 0
        repeat(1_500) {
            val m = strategy.pickMove(state)
            if (m != null) {
                if (m is Move.Compound) compounds++
                state.apply(m)
            }
        }
        assertEquals(0, compounds, "default Cbls must not emit chain compounds")
    }

    @Test
    fun `enabled chains surface as score-picked compounds on a permanently stalled search`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(-1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.GE, 7),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(7))
        state.recompute()
        val strategy = Cbls(stallChainCap = 8)
        var chainPicks = 0
        var budget = 3_000
        while (budget-- > 0 && chainPicks < 1) {
            val m = strategy.pickMove(state)
            if (m != null) {
                if (m is Move.Compound) {
                    chainPicks++
                    val vars = m.parts.map { p -> (p as Move.IntSet).varId }
                    assertEquals(vars.size, vars.toSet().size, "a chain never touches a var twice")
                }
                state.apply(m)
            }
        }
        assertTrue(chainPicks > 0, "the permanently-stalled search must surface at least one chain")
    }
}
