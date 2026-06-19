package com.eignex.klause.solver.localsearch.acceptance

import com.eignex.klause.solver.Move
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * The **acceptance axis** of an LS recipe: how a `SourceDrivenStrategy` selects one move
 * from the scored candidate pools. Lifts the selection rules that were inlined across `Cbls`,
 * `FocusedLs.MoveSelection`, and `FeasibilityJump` into one pluggable, composable type, so an arm
 * picks its acceptance independently of its sources and scoring.
 *
 * [choose] receives two pools — the **noise-eligible** moves (the stochastic draw may take these)
 * and the **score-only** moves (coordinated escapes that may be selected only deterministically,
 * never by dice) — plus a `score` function from the scoring axis (lower is better). The
 * noise/score split is enforced here uniformly: stochastic rules ([WalkSatNoise], [ProbSat]) draw
 * from the noise pool only and fall through to a deterministic best otherwise; deterministic rules
 * ([Greedy], [Skew]) range over both pools.
 *
 * Temperature-based acceptance ([Metropolis] / simulated annealing) reads the current annealing
 * temperature the driver supplies from the **schedule axis** (`ScheduleBundle.temperature`); it owns
 * no schedule itself, keeping acceptance and schedule independent.
 */
sealed interface AcceptanceRule {

    /** Choose a move from the candidate pools, or null when both are empty. `temperature` is the
     *  schedule axis's current annealing temperature (the driver supplies it from
     *  `ScheduleBundle.temperature`); only [Metropolis] consults it, the others ignore it. */
    fun choose(
        rng: Random,
        noisePool: List<Move>,
        scorePool: List<Move>,
        temperature: Double,
        score: (Move) -> Double,
    ): Move?

    /** A copy of this rule steered by the schedule axis's diversification-noise [level] in `[0, 1]`
     *  (0 = baseline, toward 1 = more random). The driver supplies the level from a
     *  `ScheduleBundle.noise` controller each pick; the noise-free rules ignore it and return self. */
    fun steered(level: Double): AcceptanceRule = this

    /** Strict greedy descent: the minimum-scored move over both pools (ties broken uniformly). */
    data object Greedy : AcceptanceRule {
        override fun choose(
            rng: Random,
            noisePool: List<Move>,
            scorePool: List<Move>,
            temperature: Double,
            score: (Move) -> Double,
        ) = bestBy(rng, noisePool, scorePool, score)
    }

    /**
     * WalkSAT-style noisy greedy (Selman 1994): with probability [noise] take a uniformly-random
     * move from the noise pool, otherwise the greedy best over both pools. Reproduces the driver's
     * former `noiseProbability` behaviour exactly (and the CBLS noise draw).
     */
    data class WalkSatNoise(
        /** Probability of taking a uniformly-random noise-pool move instead of the greedy best. */
        val noise: Double,
    ) : AcceptanceRule {
        init {
            require(noise in 0.0..1.0) { "noise ∈ [0, 1], got $noise" }
        }

        override fun choose(
            rng: Random,
            noisePool: List<Move>,
            scorePool: List<Move>,
            temperature: Double,
            score: (Move) -> Double,
        ): Move? {
            if (noisePool.isNotEmpty() && rng.nextDouble() < noise) return noisePool[rng.nextInt(noisePool.size)]
            return bestBy(rng, noisePool, scorePool, score)
        }

        /** The adaptive-noise WalkSAT: the steered level *is* the noise probability (Hoos 2002). */
        override fun steered(level: Double): AcceptanceRule = WalkSatNoise(level)
    }

    /**
     * probSAT roulette (Balint & Schöning 2012): sample the noise pool with weight
     * `(eps + score + shift)^(-cb)` — low-scored moves get exponentially more mass; the `shift` keeps
     * the base non-negative when scores go negative. Falls back to the deterministic best over the
     * score-only pool when the noise pool is empty (score-only moves are never roulette-drawn).
     */
    data class ProbSat(
        /** Break exponent: higher sharpens the distribution toward the lowest-scored moves. */
        val cb: Double = 2.06,
        /** Additive smoothing keeping the roulette base positive. */
        val eps: Double = 1.0,
    ) : AcceptanceRule {
        init {
            require(cb >= 0.0) { "cb >= 0, got $cb" }
            require(eps > 0.0) { "eps > 0, got $eps" }
        }

        override fun choose(
            rng: Random,
            noisePool: List<Move>,
            scorePool: List<Move>,
            temperature: Double,
            score: (Move) -> Double,
        ): Move? {
            if (noisePool.isEmpty()) return bestBy(rng, emptyList(), scorePool, score)
            if (noisePool.size == 1) return noisePool[0] // a lone candidate is taken unconditionally, no draw
            val scores = DoubleArray(noisePool.size) { score(noisePool[it]) }
            var minScore = scores[0]
            for (i in 1 until scores.size) if (scores[i] < minScore) minScore = scores[i]
            val shift = if (minScore < 0.0) -minScore else 0.0
            val weights = DoubleArray(noisePool.size) { (eps + scores[it] + shift).pow(-cb) }
            var total = 0.0
            for (w in weights) total += w
            if (total == 0.0) return noisePool[rng.nextInt(noisePool.size)]
            var draw = rng.nextDouble() * total
            for (i in noisePool.indices) {
                draw -= weights[i]
                if (draw <= 0.0) return noisePool[i]
            }
            return noisePool[noisePool.size - 1]
        }

        /** Adaptive probSAT: a higher steered level flattens the roulette toward uniform by scaling
         *  the break exponent down (`cb·(1 − level·0.5)`); level 0 keeps the baseline `cb`. */
        override fun steered(level: Double): AcceptanceRule = ProbSat(cb * (1.0 - level * 0.5), eps)
    }

    /**
     * Skewed-VNS (Hansen et al. 2010): greedy on `score + alpha·moveSize`, admitting a
     * slightly-worsening move whose spatial reach is small — the mechanism for crossing plateau
     * lakes. `alpha == 0.0` is exactly [Greedy].
     */
    data class Skew(
        /** Skew weight on move size; larger admits smaller worsening moves (0.0 = strict greedy). */
        val alpha: Double,
    ) : AcceptanceRule {
        init {
            require(alpha >= 0.0) { "alpha >= 0, got $alpha" }
        }

        override fun choose(
            rng: Random,
            noisePool: List<Move>,
            scorePool: List<Move>,
            temperature: Double,
            score: (Move) -> Double,
        ) = bestBy(rng, noisePool, scorePool) { score(it) + alpha * moveSize(it) }
    }

    /**
     * Simulated-annealing / Metropolis acceptance: sample the noise pool and take the first candidate
     * whose scored delta passes the Metropolis test — `delta <= 0` (always accept) or
     * `rng < exp(-delta / T)` at the `temperature` the driver supplies from the schedule axis
     * (`ScheduleBundle.temperature`). The rule is pure: it owns no schedule and does not cool — the
     * driver advances the temperature schedule per pick, keeping acceptance and schedule as
     * independent axes. Falls back to the deterministic best over the score-only pool when the noise
     * pool is empty (score-only moves are never accepted stochastically).
     */
    data object Metropolis : AcceptanceRule {
        override fun choose(
            rng: Random,
            noisePool: List<Move>,
            scorePool: List<Move>,
            temperature: Double,
            score: (Move) -> Double,
        ): Move? {
            if (noisePool.isEmpty()) return bestBy(rng, emptyList(), scorePool, score)
            repeat(noisePool.size) {
                val m = noisePool[rng.nextInt(noisePool.size)]
                val delta = score(m)
                if (delta <= 0.0 || rng.nextDouble() < exp(-delta / temperature)) return m
            }
            return noisePool[rng.nextInt(noisePool.size)]
        }
    }

    /** Shared selection helpers. */
    companion object {
        /** Move "size" for skewed acceptance: part-count for compounds, 1 for primitives. */
        private fun moveSize(move: Move): Int = when (move) {
            is Move.BoolFlip, is Move.IntSet -> 1
            is Move.Compound -> move.parts.size
        }

        /** Minimum-[key] move over both pools, ties broken by uniform reservoir sampling; null when
         *  both pools are empty. */
        private fun bestBy(rng: Random, noisePool: List<Move>, scorePool: List<Move>, key: (Move) -> Double): Move? {
            var best: Move? = null
            var bestKey = Double.POSITIVE_INFINITY
            var tieCount = 0
            for (pool in arrayOf(noisePool, scorePool)) {
                for (m in pool) {
                    val k = key(m)
                    if (k < bestKey) {
                        best = m
                        bestKey = k
                        tieCount = 1
                    } else if (k == bestKey) {
                        tieCount++
                        if (rng.nextInt(tieCount) == 0) best = m
                    }
                }
            }
            return best
        }
    }
}
