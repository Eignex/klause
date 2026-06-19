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
import kotlin.test.assertFalse

/**
 * Move-set equivalence gate for [PairSwap]. The reference closure inlines the `pairSwapStep`
 * candidate construction (the two-phase draw+validate); the test asserts [PairSwap.generate] emits
 * the identical eager candidate multiset. The minimize engine's lazy first-improving loop, which
 * calls [PairSwap.drawBoolSwap]/[PairSwap.drawIntSwap], is covered by the engine's own tests.
 */
class PairSwapEquivalenceTest {

    private val cap = 8

    /** Mixed bool/int problem with same-shaped int domains so swaps are sometimes legal. */
    private fun mixedProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 3,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 9)),
    )

    /** A starting assignment with bool variety and distinct int values so both swap kinds fire. */
    private fun prepare(state: LocalSearchState) {
        state.assignment.setBool(0, true)
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 4)
        state.assignment.setInt(2, 1)
        state.recompute()
    }

    /** The reference `pairSwapStep` candidate construction, eager-filled. */
    private fun oldPairSwapFill(state: LocalSearchState, sink: MoveSink) {
        val rng = state.rng
        val nBool = state.problem.numBoolVars
        repeat(cap) {
            val swap = run {
                if (nBool < 2) return@run null
                val a = rng.nextInt(nBool)
                val b = rng.nextInt(nBool)
                if (a == b) return@run null
                if (state.assumptions.isFrozenBool(a) || state.assumptions.isFrozenBool(b)) return@run null
                if (state.assignment.boolValue(a) == state.assignment.boolValue(b)) return@run null
                Move.Compound(listOf(Move.BoolFlip(a), Move.BoolFlip(b)))
            } ?: return@repeat
            sink.addCompound(swap.parts)
        }
        val nInt = state.problem.numIntVars
        repeat(cap) {
            val swap = run {
                if (nInt < 2) return@run null
                val a = rng.nextInt(nInt)
                val b = rng.nextInt(nInt)
                if (a == b) return@run null
                if (state.assumptions.isFrozenInt(a) || state.assumptions.isFrozenInt(b)) return@run null
                val va = state.assignment.intValue(a)
                val vb = state.assignment.intValue(b)
                if (va == vb) return@run null
                if (vb !in state.problem.intDomains[a] || va !in state.problem.intDomains[b]) return@run null
                Move.Compound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
            } ?: return@repeat
            sink.addCompound(swap.parts)
        }
    }

    @Test
    fun `PairSwap generate matches the old pairSwapStep construction`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L, 99999L)) {
            assertSourceMatchesGenerator(::mixedProblem, seed, PairSwap(cap), prepare = ::prepare) { state, sink ->
                oldPairSwapFill(state, sink)
            }
        }
    }

    @Test
    fun `PairSwap yields swap candidates on the mixed fixture`() {
        val state = freshState(mixedProblem(), 7L).also(::prepare)
        val captured = captureFromSink(state) { sink -> PairSwap(cap).generate(state, sink) }
        assertFalse(captured.isEmpty, "mixed bool/int assignment with distinct values must yield swaps")
    }
}
