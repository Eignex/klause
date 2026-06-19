package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * Random pair-swap candidate generation — the single implementation of the swap-construction logic
 * behind `LocalSearchSolver.pairSwapStep` (epic #710). A pair swap escapes plateaus where every
 * single flip breaks feasibility but a coordinated 2-flip preserves it (common in binary-decision
 * optimization like knapsack / packing). The pair set is Θ(n²), so candidates are drawn at random.
 *
 * The candidate *construction* — pick two variables, validate (distinct, unfrozen, value-compatible
 * domains), build the two-part [Move.Compound] — lives in [drawBoolSwap] / [drawIntSwap]. The
 * minimize engine consults those directly inside its own lazy first-improving loop, so its RNG draw
 * order and selection are unchanged by this extraction; [generate] is the eager fill-the-sink view
 * (up to [cap] of each kind) for any [com.eignex.klause.solver.localsearch.strategy.SourceDrivenStrategy]
 * that wants pair swaps as scored candidates.
 *
 * [Phase.Feasible] / [Pool.ScoreOnly]: pair swaps are an objective-descent move over the feasible
 * region, selected by score, never by the noise draw.
 */
class PairSwap(
    /** Candidates of each kind (bool, int) drawn per [generate] call. */
    private val cap: Int,
) : MoveSource {
    init {
        require(cap >= 0) { "cap >= 0, got $cap" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Feasible
    override val pool: Pool = Pool.ScoreOnly

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        repeat(cap) {
            val swap = drawBoolSwap(state) ?: return@repeat
            sink.addCompound(swap.parts)
        }
        repeat(cap) {
            val swap = drawIntSwap(state) ?: return@repeat
            sink.addCompound(swap.parts)
        }
    }

    /** Construction + identity. Each draw consumes exactly two RNG ints, mirroring the inline loop
     *  it replaces, so a caller threading these through its own loop preserves RNG behaviour. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("pair-swap")

        /** Draw one random bool-pair swap (a true var and a false var, both flipped), or null if the
         *  drawn pair is degenerate (same var, frozen, or equal-valued). Consumes two RNG ints. */
        fun drawBoolSwap(state: LocalSearchState): Move.Compound? {
            val nBool = state.problem.numBoolVars
            if (nBool < 2) return null
            val rng = state.rng
            val a = rng.nextInt(nBool)
            val b = rng.nextInt(nBool)
            if (a == b) return null
            if (state.assumptions.isFrozenBool(a) || state.assumptions.isFrozenBool(b)) return null
            val va = state.assignment.boolValue(a)
            val vb = state.assignment.boolValue(b)
            if (va == vb) return null
            return Move.Compound(listOf(Move.BoolFlip(a), Move.BoolFlip(b)))
        }

        /** Draw one random int-pair swap (two int vars with different values that fit in each other's
         *  domain, values exchanged), or null if the drawn pair is degenerate. Consumes two RNG ints. */
        fun drawIntSwap(state: LocalSearchState): Move.Compound? {
            val nInt = state.problem.numIntVars
            if (nInt < 2) return null
            val rng = state.rng
            val a = rng.nextInt(nInt)
            val b = rng.nextInt(nInt)
            if (a == b) return null
            if (state.assumptions.isFrozenInt(a) || state.assumptions.isFrozenInt(b)) return null
            val va = state.assignment.intValue(a)
            val vb = state.assignment.intValue(b)
            if (va == vb) return null
            if (vb !in state.problem.intDomains[a] || va !in state.problem.intDomains[b]) return null
            return Move.Compound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
        }
    }
}
