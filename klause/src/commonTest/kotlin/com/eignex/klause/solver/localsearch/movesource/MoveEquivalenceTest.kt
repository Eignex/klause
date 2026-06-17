package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the move-set equivalence harness itself — the gate every later #710 extraction relies
 * on. We have no production [MoveSource] yet (Foundation lands no wiring), so the proof is on a
 * test-only source that re-implements the violated-repair draw exactly like the generator it
 * stands in for: the harness must report equality for the faithful copy and inequality for a
 * source that draws differently. If it can't tell those apart, no later extraction is actually
 * gated.
 */
class MoveEquivalenceTest {

    /** Infeasible-by-construction linear ring (mirrors the stall fixtures): `violated` is always
     *  non-empty so the violated-repair draw has something to repair, and there is exactly one
     *  compound source (EQ channeling) — irrelevant here since these are GE/LE only, so every
     *  emitted move is a primitive repair. */
    private fun ringProblem(): Problem = Problem(
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

    /** The generator under test: one random violated factor → its repair moves. Identical to
     *  [LocalSearchState.proposeMovesFromRandomViolated]'s core, written against a supplied sink. */
    private fun violatedRepairDraw(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        val fid = state.violated.random(state.rng)
        state.factors[fid].proposeRepairMoves(state, fid, sink)
    }

    /** A faithful [MoveSource] copy of [violatedRepairDraw]. */
    private val faithful = object : MoveSource {
        override val id = MoveSourceId("test:violated-repairs")
        override val phase = Phase.Infeasible
        override val pool = Pool.NoiseEligible
        override fun generate(ctx: MoveGenContext, sink: MoveSink) {
            val state = ctx.state
            if (state.violated.isEmpty()) return
            val fid = state.violated.random(state.rng)
            state.factors[fid].proposeRepairMoves(state, fid, sink)
        }
    }

    /** A divergent source: draws *two* violated factors, consuming a different RNG sequence and
     *  emitting a different multiset. The harness must reject this. */
    private val divergent = object : MoveSource {
        override val id = MoveSourceId("test:double-draw")
        override val phase = Phase.Infeasible
        override val pool = Pool.NoiseEligible
        override fun generate(ctx: MoveGenContext, sink: MoveSink) {
            val state = ctx.state
            if (state.violated.isEmpty()) return
            repeat(2) {
                val fid = state.violated.random(state.rng)
                state.factors[fid].proposeRepairMoves(state, fid, sink)
            }
        }
    }

    @Test
    fun `harness accepts a faithful copy of the generator`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            assertSourceMatchesGenerator(::ringProblem, seed, faithful) { state, sink ->
                violatedRepairDraw(state, sink)
            }
        }
    }

    @Test
    fun `harness rejects a source that draws differently`() {
        assertFailsWith<AssertionError> {
            assertSourceMatchesGenerator(::ringProblem, seed = 7L, source = divergent) { state, sink ->
                violatedRepairDraw(state, sink)
            }
        }
    }

    @Test
    fun `the captured multiset is non-empty on the infeasible ring`() {
        val state = freshState(ringProblem(), 7L)
        val captured = captureFromSink(state) { sink -> violatedRepairDraw(state, sink) }
        assertFalse(captured.isEmpty, "the infeasible ring must yield at least one repair move")
    }

    @Test
    fun `multiset equality is order-independent and count-sensitive`() {
        val a = Move.IntSet(0, 1)
        val b = Move.IntSet(1, 2)
        assertEquals(MoveMultiset.of(listOf(a, b)), MoveMultiset.of(listOf(b, a)), "order must not matter")
        assertTrue(MoveMultiset.of(listOf(a, a)) != MoveMultiset.of(listOf(a)), "counts must matter")
        assertEquals(2, MoveMultiset.of(listOf(a, a)).size)
    }

    @Test
    fun `phase gating is declarative`() {
        assertTrue(Phase.Infeasible.appliesAt(5L))
        assertFalse(Phase.Infeasible.appliesAt(0L))
        assertTrue(Phase.Feasible.appliesAt(0L))
        assertFalse(Phase.Feasible.appliesAt(5L))
        assertTrue(Phase.Any.appliesAt(0L))
        assertTrue(Phase.Any.appliesAt(5L))
    }

    @Test
    fun `configured source rejects a negative cap`() {
        assertFailsWith<IllegalArgumentException> { ConfiguredSource(faithful, cap = -1) }
        assertEquals(4, ConfiguredSource(faithful, cap = 4).cap)
        assertTrue(ConfiguredSource(faithful).enabled)
    }
}
