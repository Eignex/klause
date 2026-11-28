package com.eignex.klause.localsearch.acceptance

import com.eignex.klause.localsearch.Move
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * The acceptance axis of a local-search recipe: how a `SourceDrivenStrategy` selects one move from the
 * scored candidate pools, independently of its sources and scoring.
 *
 * Stochastic rules draw only from the noise-eligible pool and fall back to a deterministic best; the
 * score-only pool holds coordinated escapes that are never drawn by chance. Deterministic rules range over
 * both pools. Lower scores are better.
 */
sealed interface AcceptanceRule {

    /**
     * Choose a move from the candidate pools, or null when both are empty. Only [Metropolis] reads the
     * schedule's current [temperature].
     */
    fun choose(
        rng: Random,
        noisePool: List<Move>,
        scorePool: List<Move>,
        temperature: Double,
        score: (Move) -> Double,
    ): Move?

    /** A copy steered by the schedule's diversification-noise [level] in `[0, 1]`; noise-free rules return self. */
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
     * Strict-improvement greedy: the minimum-scored move only when `score < 0`, else null so the engine
     * restarts instead of committing a worsening move.
     */
    data object GreedyDescent : AcceptanceRule {
        override fun choose(
            rng: Random,
            noisePool: List<Move>,
            scorePool: List<Move>,
            temperature: Double,
            score: (Move) -> Double,
        ): Move? {
            val best = bestBy(rng, noisePool, scorePool, score) ?: return null
            return if (score(best) < 0.0) best else null
        }
    }

    /**
     * WalkSAT-style noisy greedy: with probability [noise] take a uniformly-random move from the noise
     * pool, otherwise the greedy best over both pools. B. Selman, H. Kautz, B. Cohen, "Noise strategies
     * for improving local search", AAAI 1994, https://mlanthology.org/aaai/1994/selman1994aaai-noise.
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

        /** Adaptive-noise WalkSAT: the steered level *is* the noise probability. H. H. Hoos, "An adaptive
         *  noise mechanism for WalkSAT", AAAI 2002, https://www.cs.ubc.ca/~hoos/Publ/Hoos02c-err.pdf. */
        override fun steered(level: Double): AcceptanceRule = WalkSatNoise(level)
    }

    /**
     * probSAT roulette: sample the noise pool with weight `(eps + score + shift)^(-cb)`, where `shift`
     * keeps the base non-negative. A. Balint, U. Schöning, "Choosing probability
     * distributions for stochastic local search and the role of make versus break", SAT 2012,
     * https://doi.org/10.1007/978-3-642-31612-8_3.
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
            if (noisePool.size == 1) return noisePool[0]
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

        /** A higher level flattens the roulette toward uniform by scaling the break exponent down. */
        override fun steered(level: Double): AcceptanceRule = ProbSat(cb * (1.0 - level * 0.5), eps)
    }

    /**
     * Skewed-VNS acceptance: greedy on `score + alpha·moveSize`, so small moves can cross plateaus.
     * `alpha == 0.0` is exactly [Greedy]. P. Hansen, N. Mladenović, J. A. Moreno Pérez, "Variable
     * neighbourhood search: methods and applications", Annals of Operations Research 175, 2010,
     * https://doi.org/10.1007/s10479-009-0657-6.
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
     * Metropolis acceptance: sample the noise pool and take the first candidate with `delta <= 0` or
     * `rng < exp(-delta / temperature)`. The driver owns the cooling schedule. S. Kirkpatrick, C. D. Gelatt,
     * M. P. Vecchi, "Optimization by simulated annealing", Science 220, 1983,
     * https://doi.org/10.1126/science.220.4598.671.
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
        private fun moveSize(move: Move): Int = when (move) {
            is Move.BoolFlip, is Move.IntSet, is Move.RealSet -> 1
            is Move.Compound -> move.parts.size
        }

        // Ties are broken by uniform reservoir sampling.
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
