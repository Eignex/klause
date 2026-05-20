package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Scaling And Probabilistic Smoothing (Hutter-Tompkins-Hoos 2002). A dynamic local search
 * with clause-weight learning that only fires when the search hits a local minimum,
 * sidestepping DDFW's every-step weight pass.
 *
 * Per step:
 *  1. Score every move proposed by a randomly-chosen violated factor by its weighted gain
 *     `Σ w[f] · Δviolated[f]` (negative = improvement). Pick the lowest.
 *  2. If that move strictly improves (gain < 0): take it.
 *  3. Otherwise we're in a (possibly weighted) local minimum. With probability [walkProb]
 *     pick a uniformly random move; otherwise:
 *       a. **Scale**: multiply the weight of every currently-violated factor by [alpha].
 *       b. **Smooth (with probability [smoothProb])**: pull all weights toward
 *          [initWeight] by `w ← (1 - smoothFactor) · w + smoothFactor · initWeight`.
 *       c. Pick the best move under the new weights.
 *
 * The scale-on-minimum schedule is what distinguishes SAPS from DDFW: DDFW shuffles weight
 * between satisfied and unsatisfied neighbours every step and conserves total weight; SAPS
 * only injects weight when stuck and uses smoothing as a forgetting mechanism for stale
 * pressure. On pure-SAT benchmarks SAPS is competitive with WalkSAT and faster than DDFW
 * on hard random 3-SAT, though probSAT generally dominates on uniform random instances.
 *
 * Defaults are the paper's recommended values for SAT (`α=1.3, ρ=0.4, smoothFactor=0.8,
 * wp=0.01`). Tune `walkProb` upward for very plateau-heavy landscapes; tune `alpha` down
 * (toward 1.05) when factor weights overflow on long runs.
 */
open class Saps(
    val alpha: Double = 1.3,
    val smoothProb: Double = 0.4,
    val smoothFactor: Double = 0.8,
    val walkProb: Double = 0.01,
    val initWeight: Double = 1.0,
    val tabu: TabuFilter = TabuFilter.Disabled,
) : Strategy {

    init {
        require(alpha > 1.0) { "alpha must be > 1, got $alpha" }
        require(smoothProb in 0.0..1.0) { "smoothProb must be in [0,1], got $smoothProb" }
        require(smoothFactor in 0.0..1.0) { "smoothFactor must be in [0,1], got $smoothFactor" }
        require(walkProb in 0.0..1.0) { "walkProb must be in [0,1], got $walkProb" }
        require(initWeight > 0.0) { "initWeight must be > 0, got $initWeight" }
    }

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val w = state.factorWeights
        // Lazy init: factorWeights is allocated to 0.0; bring untouched entries up to
        // initWeight. Cheap to scan once per step — only the first step pays in full.
        if (initWeight != 0.0) {
            for (i in w.indices) if (w[i] == 0.0) w[i] = initWeight
        }

        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val moves = tabu.filter(state, raw)
        var best = moves[0]
        var bestScore = weightedGain(state, best)
        for (i in 1 until moves.size) {
            val s = weightedGain(state, moves[i])
            if (s < bestScore) {
                best = moves[i]
                bestScore = s
            }
        }

        if (bestScore < 0.0) return best  // strictly improving — take it

        // Local minimum: scale (or walk), then re-score.
        if (state.rng.nextDouble() < walkProb) {
            return moves[state.rng.nextInt(moves.size)]
        }
        scaleViolated(state)
        if (state.rng.nextDouble() < smoothProb) smoothAll(state)

        // Re-score after weight update. Same move set — the proposing factor was already
        // chosen; SAPS doesn't re-pick the factor after a scale.
        best = moves[0]
        bestScore = weightedGain(state, best)
        for (i in 1 until moves.size) {
            val s = weightedGain(state, moves[i])
            if (s < bestScore) {
                best = moves[i]
                bestScore = s
            }
        }
        return best
    }

    /** Weighted change in violated-factor cost: `Σ w[f] · delta`. Compound moves
     *  approximate by summing per-part contributions (over-counts cancellations across
     *  parts, same trade-off Ddfw makes). Folds in shaped-objective delta so SAPS is
     *  objective-aware when cost shaping is on. */
    private fun weightedGain(state: LocalSearchState, move: Move): Double {
        val w = state.factorWeights
        val base = when (move) {
            is Move.BoolFlip -> {
                var sum = 0.0
                state.forEachBoolFactorDelta(move.varId) { fid, d -> sum += w[fid] * d }
                sum
            }
            is Move.IntSet -> {
                var sum = 0.0
                state.forEachIntFactorDelta(move.varId, move.newValue) { fid, d ->
                    sum += w[fid] * d
                }
                sum
            }
            is Move.Compound -> move.parts.sumOf { weightedGain(state, it) }
        }
        return base + state.shapedObjectiveDelta(move)
    }

    private fun scaleViolated(state: LocalSearchState) {
        val w = state.factorWeights
        val violated = state.violated
        for (i in 0 until violated.size) {
            val f = violated[i]
            w[f] *= alpha
        }
    }

    private fun smoothAll(state: LocalSearchState) {
        val w = state.factorWeights
        val keep = 1.0 - smoothFactor
        val pull = smoothFactor * initWeight
        for (i in w.indices) w[i] = keep * w[i] + pull
    }
}
