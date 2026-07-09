package com.eignex.klause.localsearch.movesource

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

/**
 * Feasibility-Jump / ViolationLS candidate generation. Where the step-based sources
 * ([ViolatedRepairs], [Frontier]) propose ±1 / repair-suggested moves, this source jumps a hot-spot
 * variable directly to the value that minimizes the weighted sum of the constraint violations it
 * participates in.
 *
 * Per call it samples [candidateVars] variables biased toward currently-violated factors (a random
 * violated factor → a random variable of it) and, for each, emits the single best jump:
 *  - int var: the domain value v* minimizing `Σ weight(f)·Δdegree(f)` over the factors f touching
 *    the variable, computed with [LocalSearchState.forEachIntFactorDelta]. Small domains are swept
 *    exactly; wide domains are sampled up to [maxValueTries]. The current value is always a
 *    candidate (Δ = 0), so the emitted jump never increases weighted violation.
 *  - bool var: the flip, emitted only when its weighted violation delta is strictly negative.
 *
 * The jump is scored against the adaptive per-constraint weights in
 * [com.eignex.klause.localsearch.FactorWeightBook.factorWeights];
 * reading it forces the lazy allocation, which is intended for a weighted-violation method.
 */
class ArgminJump(
    /** Hot-spot variables sampled (and jumped) per [generate] call. */
    private val candidateVars: Int,
    /** Cap on domain values evaluated per int variable when the domain is wider than this. */
    private val maxValueTries: Int = DEFAULT_MAX_VALUE_TRIES,
) : MoveSource {
    init {
        require(candidateVars >= 1) { "candidateVars >= 1, got $candidateVars" }
        require(maxValueTries >= 1) { "maxValueTries >= 1, got $maxValueTries" }
    }

    override val id: MoveSourceId = ID
    override val phase: Phase = Phase.Any
    override val pool: Pool = Pool.NoiseEligible

    override fun generate(state: LocalSearchState, sink: MoveSink) {
        if (state.violated.isEmpty()) return
        val weights = state.weights.factorWeights
        repeat(candidateVars) {
            val fid = state.violated.random(state.rng)
            val scope = state.problem.factors[fid]
            val nInt = scope.intVars.size
            val nBool = scope.boolVars.size
            if (nInt + nBool == 0) return@repeat
            val pick = state.rng.nextInt(nInt + nBool)
            if (pick < nInt) {
                emitBestIntJump(state, weights, scope.intVars[pick], sink)
            } else {
                val v = scope.boolVars[pick - nInt]
                if (weightedBoolFlipDelta(state, weights, v) < 0.0) sink.addBoolFlip(v)
            }
        }
    }

    private fun emitBestIntJump(state: LocalSearchState, weights: DoubleArray, v: Int, sink: MoveSink) {
        val cur = state.assignment.intValue(v)
        val d = state.problem.intDomains[v]
        var bestVal = cur
        // Staying put is the baseline candidate (Δ = 0); a jump is taken only if it strictly beats it.
        var bestDelta = 0.0
        if (d.size <= maxValueTries) {
            for (idx in 0 until d.size) {
                val candidate = d.valueAt(idx)
                if (candidate == cur) continue
                val delta = weightedIntSetDelta(state, weights, v, candidate)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestVal = candidate
                }
            }
        } else {
            repeat(maxValueTries) {
                val candidate = d.valueAt(state.rng.nextInt(d.size))
                if (candidate == cur) return@repeat
                val delta = weightedIntSetDelta(state, weights, v, candidate)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestVal = candidate
                }
            }
        }
        if (bestVal != cur) sink.addChannelingIntSet(state, v, bestVal)
    }

    private fun weightedIntSetDelta(state: LocalSearchState, weights: DoubleArray, v: Int, newValue: Long): Double {
        var wd = 0.0
        state.forEachIntFactorDelta(v, newValue) { fid, delta -> wd += weights[fid] * delta }
        return wd
    }

    private fun weightedBoolFlipDelta(state: LocalSearchState, weights: DoubleArray, v: Int): Double {
        var wd = 0.0
        state.forEachBoolFactorDelta(v) { fid, delta -> wd += weights[fid] * delta }
        return wd
    }

    /** Catalog identity and defaults. */
    companion object {
        /** Catalog id for this source. */
        val ID: MoveSourceId = MoveSourceId("argmin-jump")

        /** Default cap on domain values evaluated per wide-domain int variable. */
        const val DEFAULT_MAX_VALUE_TRIES: Int = 16
    }
}
