package com.eignex.klause.localsearch.strategy

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CblsStallSwapTest {

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

    /** Drives up to [steps] picks. The positive tests only need a handful of validated swap
     *  compounds, not the full stalled drive — they pass [compoundCap] to stop early once
     *  enough have surfaced (the negative default-cap test must ride out every step). */
    private fun drive(
        strategy: SourceDrivenStrategy,
        state: LocalSearchState,
        steps: Int,
        compoundCap: Int = Int.MAX_VALUE,
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
    fun `default stallSwapCap 0 never emits a stall swap`() {
        val state = LocalSearchState(stallProblem().bake(), Random(7))
        var compounds = 0
        drive(Cbls(), state, steps = 2_000) { if (it is Move.Compound) compounds++ }
        assertEquals(0, compounds, "default Cbls must not emit swap compounds on a compound-free problem")
    }

    @Test
    fun `enabled swaps are legal same-domain two-part value exchanges`() {
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
                // A value exchange: each var receives the other's current value.
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
    fun `swaps never touch frozen vars`() {
        val frozen = Assumptions(ints = mapOf(2 to 3))
        val state = LocalSearchState(stallProblem().bake(), Random(7), frozen)
        drive(Cbls(stallSwapCap = 16), state, steps = 10_000, compoundCap = 5) { m ->
            if (m is Move.Compound) {
                for (p in m.parts) {
                    assertTrue(p is Move.IntSet, "stall swap parts must be int sets")
                    assertTrue(p.varId != 2, "swap must not touch a frozen var")
                }
            }
        }
    }
}
