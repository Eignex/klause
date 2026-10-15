package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CblsTest {

    // GE/LE factors exclude EQ channeling, so compounds can only be stall swaps. The sum sandwich
    // x0 + x1 = 3 makes every single-variable move worse; swapping preserves the sum while exchanging
    // which contradictory difference row holds. Variable x2 supports the frozen-variable case.
    private fun stallProblem(): Problem = Problem(
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

    private fun drive(
        strategy: SourceDrivenStrategy,
        state: LocalSearchState,
        steps: Int,
        compoundCap: Int,
        onMove: (Move) -> Unit,
    ) {
        state.recompute()
        var compounds = 0
        repeat(steps) {
            // A null pick is "no candidate this step" (the engine would restart); keep
            // driving — the RNG advances inside pickMove, so the next call resamples.
            val m = strategy.pickMove(state)
            if (m != null) {
                onMove(m)
                state.apply(m)
                if (m is Move.Compound && ++compounds >= compoundCap) return
            }
        }
    }

    @Test
    fun `enabled swaps should be legal same-domain two-part value exchanges`() {
        val problem = stallProblem()
        val state = LocalSearchState(problem.bake(), Random(7))
        var swaps = 0
        drive(Cbls(stallSwapCap = 16), state, steps = 10_000, compoundCap = 5) { m ->
            if (m is Move.Compound) {
                swaps++
                assertEquals(2, m.parts.size, "stall swap must be a two-part compound")
                val a = m.parts[0]
                val b = m.parts[1]
                assertTrue(a is Move.IntSet && b is Move.IntSet, "stall swap parts must be int sets")
                assertEquals(state.assignment.intValue(b.varId), a.newValue, "swap must exchange values")
                assertEquals(state.assignment.intValue(a.varId), b.newValue, "swap must exchange values")
                val da = state.rootDomains[a.varId]
                val db = state.rootDomains[b.varId]
                assertEquals(da.min, db.min, "swap pairs must share domain bounds")
                assertEquals(da.max, db.max, "swap pairs must share domain bounds")
            }
        }
        assertTrue(swaps > 0, "the permanently-stalled search must surface at least one swap")
    }

    @Test
    fun `swaps should never touch frozen vars`() {
        val frozen = Assumptions(ints = mapOf(2 to 3))
        val state = LocalSearchState(stallProblem().bake(), Random(7), frozen)
        drive(Cbls(stallSwapCap = 16), state, steps = 10_000, compoundCap = 5) { m ->
            if (m is Move.Compound) {
                for (p in m.parts) {
                    assertTrue(p is Move.IntSet, "stall swap parts must be int sets")
                    assertTrue(p.varId != 2)
                }
            }
        }
    }

    @Test
    fun `default cbls should descend through an owned global among arithmetic rows`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), 0, 3)) + Array<Factor>(256) {
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
            },
        )
        val state = LocalSearchState(problem.bake(), Random(7))
        state.seedImplicitFeasible()
        for (v in 0..2) state.assignment.setInt(v, v.toLong())
        state.recompute()
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0, 0))
        state.shaping.objective = objective

        val move = assertNotNull(Cbls().pickMove(state))

        assertTrue(checkNotNull(state.objectiveDelta(objective, move)) < 0.0)
        assertEquals(0L, state.netDelta(move))
        state.apply(move)
        state.recompute()
        assertEquals(0L, state.cost)
    }
}
