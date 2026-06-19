package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.proposeRepairChains
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mechanics of the opt-in ejection chains ([Cbls.stallChainCap] /
 * [LocalSearchState.proposeRepairChains]): chains must be directed (each step repairs the
 * damage of the previous one) and emitted as atomic compounds; construction must leave the
 * state exactly as it found it; the default (`0`) must keep behavior unchanged; frozen vars
 * must never appear in a chain.
 */
class CblsStallChainTest {

    /**
     * Two-step chain fixture. Vars `x0, x1 ∈ [0, 3]`, start `(0, 2)`:
     *  - F0: `x0 ≥ 2` — violated (degree 2). Its snap repair `x0 → 2`…
     *  - F1: `x0 + x1 ≤ 2` — satisfied at start; …newly regresses to degree 2 (cost-neutral
     *    step), and its only eligible repair avoiding the pinned `x0` is `x1 → 0`, which
     *    completes a strictly-improving two-part chain (cost 2 → 0).
     * No single move improves: `x0 → 2` alone is Δ0, every other single repair is Δ ≥ 0.
     */
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
        val state = LocalSearchState(problem, Random(7), assumptions)
        for (i in vals.indices) state.assignment.setInt(i, vals[i])
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
        // The repair-first walk must derive the directed two-step chain: repair(F0) followed
        // by the repair of the factor it regressed (F1). Ejection-first chains (neighbour
        // primitives) may add further compounds; the directed one must be among them.
        val expected = listOf(Move.IntSet(0, 2), Move.IntSet(1, 0))
        val chain = sink.list.filterIsInstance<Move.Compound>().firstOrNull { it.parts == expected }
        assertTrue(chain != null, "the directed repair chain $expected must be emitted (got ${sink.list})")
        // Construction is apply-evaluate-revert: the state must be exactly as before.
        assertEquals(costBefore, state.cost, "cost must be restored")
        assertEquals(stepBefore, state.step, "step must be restored")
        assertEquals(0, state.assignment.intValue(0), "x0 must be restored")
        assertEquals(2, state.assignment.intValue(1), "x1 must be restored")
        // The emitted chain must actually be strictly improving when applied for real.
        state.apply(chain)
        assertEquals(0L, state.cost, "the chain must solve the fixture")
    }

    @Test
    fun `chains never touch frozen vars`() {
        // Freeze x1: the chain's second step is filtered, leaving no eligible repair, so the
        // walk ends cost-neutral after the first part — a 1-part chain, which is discarded.
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
        // The swap test's compound-free invariant extends to chains: a default Cbls run on a
        // permanently-stalled compound-free problem must emit no compounds at all.
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
        val state = LocalSearchState(problem, Random(7))
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
        // The swap test's exchange-ring fixture (including the permanently-violated x2 bound
        // that keeps the stall machinery engaged): every single move breaks the sum sandwich
        // (Δ ≥ +1); the only Δ0 escape is the coordinated corner exchange. With swaps off and
        // chains on, the only compound source is the chain machinery — chains must *derive*
        // the corner exchange from the break structure and get score-picked.
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
        val state = LocalSearchState(problem, Random(7))
        state.recompute()
        val strategy = Cbls(stallChainCap = 8)
        var chainPicks = 0
        var budget = 3_000
        // One validated chain pick proves the machinery engages and derives a well-formed
        // (no-repeated-var) compound; stop at the first to avoid riding the stalled search.
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
