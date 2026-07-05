package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink

/**
 * Random pair-swap candidate generation. A pair swap escapes plateaus where every single flip
 * breaks feasibility but a coordinated 2-flip preserves it (common in binary-decision optimization
 * like knapsack / packing). The pair set is Θ(n²), so candidates are drawn at random.
 *
 * The candidate construction — pick two variables, validate (distinct, unfrozen, value-compatible
 * domains), build the two-part [Move.Compound] — lives in [drawBoolSwap] / [drawIntSwap]. The
 * minimize engine consults those directly inside its own lazy first-improving loop; [generate] is
 * the eager fill-the-sink view (up to [cap] of each kind) for any strategy that wants pair swaps as
 * scored candidates.
 *
 * [Phase.Feasible] / [Pool.ScoreOnly]: pair swaps are an objective-descent move over the feasible
 * region, selected by score, never by the noise draw.
 */
class PairSwap(
    /** Candidates of each kind (bool, int) drawn per [generate] call. */
    private val cap: Int,
    /** When true, the first endpoint of each int swap is drawn from the objective hot-spot
     *  ([LocalSearchState.objectiveHotSpotIntVar]) so swaps concentrate on objective-relevant
     *  variables; false keeps the uniform-random draw. No effect once the objective exposes no int
     *  gradient (the draw falls back to uniform). */
    private val hotSpot: Boolean = false,
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
            val swap = drawIntSwap(state, hotSpot) ?: return@repeat
            sink.addCompound(swap.parts)
        }
    }

    /** Construction + identity. Each draw consumes exactly two RNG ints, so a caller threading these
     *  through its own loop preserves RNG behaviour. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("pair-swap")

        /** Objective-hot-spot variant: bias the first int-swap endpoint toward objective-relevant
         *  variables. */
        fun hotSpot(cap: Int): PairSwap = PairSwap(cap, hotSpot = true)

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
         *  domain, values exchanged), or null if the drawn pair is degenerate. Consumes two RNG ints.
         *  When [hotSpot] is set, the first endpoint is drawn from the objective hot-spot (falling back
         *  to uniform when the objective exposes no int gradient). */
        fun drawIntSwap(state: LocalSearchState, hotSpot: Boolean = false): Move.Compound? {
            val nInt = state.problem.numIntVars
            if (nInt < 2) return null
            val rng = state.rng
            val hot = if (hotSpot) state.objectiveHotSpotIntVar(rng) else -1
            val a = if (hot >= 0) hot else rng.nextInt(nInt)
            val b = rng.nextInt(nInt)
            return intSwapMove(state, a, b, sameShape = false, checkOwner = true)
        }

        /**
         * The value-exchange move for a chosen int-var pair `(u, w)` — the single construction point
         * for an int pair-swap ([drawIntSwap]'s uniform draw and [StallSwaps]' violated-factor draw
         * both call it after their own selection, so the RNG sequence stays with the caller). Returns
         * null (reject) when the pair is equal, either var is frozen, [checkOwner] and either is owned
         * by an implicitly-solved global, [sameShape] and the domains differ in `[min, max]`, the
         * values are equal, or a swapped value falls outside the other's domain. None of these draw
         * RNG, so the gate order is behaviour-irrelevant.
         */
        fun intSwapMove(
            state: LocalSearchState,
            u: Int,
            w: Int,
            sameShape: Boolean,
            checkOwner: Boolean,
        ): Move.Compound? {
            if (u == w) return null
            if (state.assumptions.isFrozenInt(u) || state.assumptions.isFrozenInt(w)) return null
            if (checkOwner) {
                // Owned by an implicitly-solved global: only that global may move it (a blind swap
                // would break the constraint it was seeded feasible into).
                val owners = state.ownerInt
                if (owners != null && (owners[u] >= 0 || owners[w] >= 0)) return null
            }
            val du = state.problem.intDomains[u]
            val dw = state.problem.intDomains[w]
            // Same-shaped domains only (swaps target permutation/assignment structure over one value
            // range); cross-domain swaps (decision var vs derived load/count var) are meaningless.
            if (sameShape && (du.min != dw.min || du.max != dw.max)) return null
            val vu = state.assignment.intValue(u)
            val vw = state.assignment.intValue(w)
            if (vu == vw) return null
            if (vw !in du || vu !in dw) return null
            return Move.Compound(listOf(Move.IntSet(u, vw), Move.IntSet(w, vu)))
        }
    }
}
