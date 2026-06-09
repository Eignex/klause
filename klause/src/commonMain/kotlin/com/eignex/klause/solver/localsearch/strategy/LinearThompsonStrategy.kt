package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.kumulant.bandit.contextual.LinearRegressionSpec
import com.eignex.kumulant.bandit.contextual.RegressionContextualBandit
import com.eignex.kumulant.bandit.contextual.RegressionContextualSpec
import com.eignex.kumulant.bandit.materialize
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.math.DenseVector
import com.eignex.kumulant.stat.regression.glm.MultivariateGaussian
import kotlin.math.min
import kotlin.random.Random

/**
 * Move-selection [Strategy] driven by an online contextual bandit (issue #8): instead of the
 * hand-coded WalkSAT/probSAT scoring, a kumulant [RegressionContextualBandit] (Linear Thompson
 * Sampling over a Bayesian linear-regression posterior — the [MultivariateGaussian] posterior,
 * a drop-in for the LinUCB posterior the CP `RegressionVariableHeuristic` uses) learns, **per
 * session**, a value function over per-move features and picks the candidate with the highest
 * sampled score.
 *
 * It mirrors `RegressionVariableHeuristic`: one logical arm whose context is the per-move feature
 * vector; score every candidate via [RegressionContextualBandit.evaluate]; reward the previous
 * move at the next call once its outcome (the cost change it caused) is observable.
 *
 * **Soundness:** it only chooses *which* repair move to apply among the candidates the violated
 * factor proposes (or returns null to restart, like the other strategies), so it can never make
 * an unsound move — only the move *quality* depends on what the bandit learns.
 *
 * **Reward** = the drop in cost the chosen move produced (`costBefore − costAfter`, normalised by
 * the largest magnitude seen so far into roughly [-1, 1]); a move that reduced violations scores
 * positive, one that worsened them negative. The posterior lives on this instance, so it persists
 * across the session's calls and restarts (the learned move-value function should survive a
 * restart of the assignment).
 *
 * **Cost guard:** at most [scoreCap] candidates are scored per step (repair proposals are usually
 * small, but a high-arity factor can propose many); the rest are ignored that step.
 */
class LinearThompsonStrategy private constructor(
    private val bandit: RegressionContextualBandit<*>,
    private val scoreCap: Int,
) : Strategy {

    private var pendingFeatures: DoubleArray? = null
    private var pendingCostBefore: Long = 0L
    private var rewardScale: Double = 1.0

    override fun pickMove(state: LocalSearchState): Move? {
        rewardPending(state)

        val raw = state.proposeMovesFromRandomViolated()
        if (raw.isNullOrEmpty()) {
            pendingFeatures = null
            return null
        }
        val candidates = if (raw.size <= scoreCap) raw else raw.subList(0, scoreCap)

        var best: Move? = null
        var bestFeatures: DoubleArray? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (move in candidates) {
            val f = features(state, move)
            val score = bandit.evaluate(0, DenseVector.of(f))
            if (score > bestScore) {
                bestScore = score
                best = move
                bestFeatures = f
            }
        }
        pendingFeatures = bestFeatures
        pendingCostBefore = state.cost
        return best
    }

    private fun rewardPending(state: LocalSearchState) {
        val f = pendingFeatures ?: return
        val drop = (pendingCostBefore - state.cost).toDouble()
        if (drop > rewardScale || -drop > rewardScale) rewardScale = if (drop < 0) -drop else drop
        val reward = (drop / rewardScale).coerceIn(-1.0, 1.0)
        bandit.update(0, DenseVector.of(f), reward, 1.0)
        pendingFeatures = null
    }

    private fun features(state: LocalSearchState, move: Move): DoubleArray {
        val slot = primarySlot(state, move)
        val degree = degreeOf(state, move)
        val untouched = if (slot < 0) RECENCY_WINDOW.toLong() else state.step - state.lastTouched[slot]
        val recency = min(untouched.toDouble() / RECENCY_WINDOW, 1.0)
        return doubleArrayOf(
            state.breakScore(move).toDouble() / BREAK_SCALE, // fewer freshly-broken factors is better
            state.netDelta(move).toDouble() / NET_SCALE, // negative = cost-reducing
            state.shapedObjectiveDelta(move) / OBJ_SCALE, // 0 in the satisfy phase
            recency, // long-untouched moves escape cycles
            if (confChange(state, move)) 1.0 else 0.0, // CCASat configuration-changed flag
            min(degree / DEGREE_SCALE, 1.0), // factor-graph degree
        )
    }

    /** Variable slot of the move's first primitive (bool ids first, int ids offset by numBoolVars);
     *  -1 for an empty compound. Slot-based features use this representative variable. */
    private fun primarySlot(state: LocalSearchState, move: Move): Int = when (move) {
        is Move.BoolFlip -> move.varId
        is Move.IntSet -> state.problem.numBoolVars + move.varId
        is Move.Compound -> move.parts.firstOrNull()?.let { primarySlot(state, it) } ?: -1
    }

    private fun confChange(state: LocalSearchState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]
        is Move.IntSet -> state.intConfChange[move.varId]
        is Move.Compound -> move.parts.any { confChange(state, it) }
    }

    private fun degreeOf(state: LocalSearchState, move: Move): Int = when (move) {
        is Move.BoolFlip -> state.problem.boolOccurrences[move.varId].size
        is Move.IntSet -> state.problem.intOccurrences[move.varId].size
        is Move.Compound -> move.parts.sumOf { degreeOf(state, it) }
    }

    /** Feature-vector size and the [thompson] factory. */
    companion object {
        /** Number of per-move features in the LinUCB/Thompson context vector (see [features]). */
        const val FEATURE_SIZE: Int = 6
        private const val BREAK_SCALE = 8.0
        private const val NET_SCALE = 8.0
        private const val OBJ_SCALE = 8.0
        private const val DEGREE_SCALE = 16.0
        private const val RECENCY_WINDOW = 64.0

        /** Build a Linear-Thompson move strategy. [priorVariance] sets the Bayesian prior,
         *  [exploration] scales the Thompson sampling variance, [scoreCap] bounds per-step scoring. */
        fun thompson(
            seed: Long = 0L,
            exploration: Double = 1.0,
            priorVariance: Double = 1.0,
            scoreCap: Int = 32,
        ): LinearThompsonStrategy {
            val regression = LinearRegressionSpec.Bayesian(FEATURE_SIZE, priorVariance)
            val spec = RegressionContextualSpec(1, regression, MultivariateGaussian, exploration, regression)
            return LinearThompsonStrategy(spec.materialize(Random(seed), Concurrency.None), scoreCap)
        }
    }
}
