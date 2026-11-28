package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.VarRef
import com.eignex.koblas.DenseVector
import com.eignex.kumulant.bandit.contextual.RegressionContextualBandit
import com.eignex.kumulant.bandit.contextual.RegressionContextualSpec
import com.eignex.kumulant.bandit.materialize
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.schema.spec.BayesianRegression
import com.eignex.kumulant.stat.regression.glm.LinUcb
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Contextual-bandit variable heuristic: a [RegressionContextualBandit] (LinUCB over a Bayesian
 * linear-regression posterior; Li, Chu, Langford and Schapire, "A contextual-bandit approach to personalized
 * news article recommendation", WWW 2010, https://doi.org/10.1145/1772690.1772758) learns, per session, a
 * value function over per-variable branching features and picks the highest-scoring unassigned variable.
 *
 * At most [scoreCap] candidates are scored per decision, smallest domains first. A branch is rewarded at
 * the next decision: 1.0 if it led straight to a conflict, else its propagation squashed into `[0, 1)`.
 */
class RegressionVariableSelector private constructor(
    private val newBandit: () -> RegressionContextualBandit<*>,
    private val scoreCap: Int,
) : VariableSelector {

    private val bandit: RegressionContextualBandit<*> = newBandit()

    /** A fresh selector with the same config and an unlearned bandit. */
    override fun fresh() = RegressionVariableSelector(newBandit, scoreCap)

    // The previous decision, rewarded at the next pick once its outcome is observable.
    private var pendingFeatures: DoubleArray? = null
    private var pendingPropCount: Long = 0L
    private var conflictSincePick: Boolean = false

    // Self-maintained activity and last-conflict step, since the engine exposes neither to a heuristic.
    // As features they let the linear model express VSIDS and last-conflict as well as fail-first.
    // Slots: bools first, then ints offset by numBoolVars.
    private var activity: DoubleArray? = null
    private var lastConflict: LongArray? = null
    private var numBoolVars: Int = 0
    private var conflicts: Long = 0L
    private var bumpInc: Double = 1.0
    private var maxActivity: Double = 1.0

    // Instance-relative scales so the unbounded structural features land in ~[0, 1].
    private var degreeScale: Double = 1.0
    private var lnDomScale: Double = 1.0
    private var depthScale: Double = 1.0

    override fun pick(session: PropagationSession, rng: Random): VarRef? {
        ensureState(session)
        rewardPending(session)

        val candidates = collectCandidates(session)
        if (candidates.isEmpty()) {
            pendingFeatures = null
            return null
        }
        var best: VarRef? = null
        var bestFeatures: DoubleArray? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in candidates) {
            val f = features(session, c)
            val score = bandit.evaluate(0, DenseVector.wrap(f))
            if (score > bestScore) {
                bestScore = score
                best = c.ref
                bestFeatures = f
            }
        }
        pendingFeatures = bestFeatures
        pendingPropCount = session.propagationCount
        conflictSincePick = false
        return best
    }

    private fun rewardPending(session: PropagationSession) {
        val f = pendingFeatures ?: return
        val reward = if (conflictSincePick) {
            1.0
        } else {
            val delta = (session.propagationCount - pendingPropCount).coerceAtLeast(0L).toDouble()
            (delta / (delta + PROP_SQUASH)).coerceIn(0.0, 1.0)
        }
        bandit.update(0, DenseVector.wrap(f), reward, 1.0)
        pendingFeatures = null
    }

    private class Candidate(val ref: VarRef, val domSize: Int)

    private fun collectCandidates(session: PropagationSession): List<Candidate> {
        val problem = session.problem
        val all = ArrayList<Candidate>()
        for (v in 0 until problem.numBoolVars) {
            if (session.needsBoolDecision(v)) all.add(Candidate(VarRef.Bool(v), 2))
        }
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (!session.needsIntDecision(v)) continue
            all.add(Candidate(VarRef.IntVar(v), d.valueCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }
        if (all.size <= scoreCap) return all
        return all.sortedBy { it.domSize }.subList(0, scoreCap)
    }

    private fun features(session: PropagationSession, c: Candidate): DoubleArray {
        val problem = session.problem
        val isBool = c.ref is VarRef.Bool
        val degree = if (isBool) {
            session.projection.boolOccurrences[c.ref.varId].size
        } else {
            session.projection.intOccurrences[c.ref.varId].size
        }
        val slot = if (isBool) c.ref.varId else numBoolVars + c.ref.varId
        val act = checkNotNull(activity)[slot] / maxActivity
        val lc = checkNotNull(lastConflict)[slot]
        val recency = if (lc < 0L) 0.0 else 1.0 / (1.0 + (conflicts - lc))
        return doubleArrayOf(
            1.0 / c.domSize,
            ln((c.domSize + 1).toDouble()) / lnDomScale,
            min(degree / degreeScale, 1.0),
            min(session.decisionLevel / depthScale, 1.0),
            if (isBool) 1.0 else 0.0,
            act,
            recency,
        )
    }

    // Problem size is unknown at construction, so state is sized on the first pick.
    private fun ensureState(session: PropagationSession) {
        if (activity != null) return
        val problem = session.problem
        numBoolVars = problem.numBoolVars
        val n = numBoolVars + problem.numIntVars
        activity = DoubleArray(n)
        lastConflict = LongArray(n) { -1L }
        var maxDegree = 1
        for (v in 0 until problem.numBoolVars) maxDegree = maxOf(maxDegree, session.projection.boolOccurrences[v].size)
        for (v in 0 until problem.numIntVars) maxDegree = maxOf(maxDegree, session.projection.intOccurrences[v].size)
        degreeScale = maxDegree.toDouble()
        var maxDom = 2
        for (v in 0 until problem.numIntVars) {
            val values = session.rootIntDomain(v).valueCount
            maxDom = maxOf(maxDom, values.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        lnDomScale = ln((maxDom + 1).toDouble())
        depthScale = maxOf(1, n).toDouble()
    }

    // Growing the increment decays every prior activity without an O(n) pass.
    private fun bumpConflict(boolVars: IntArray, intVars: IntArray) {
        val act = activity ?: return
        val lc = lastConflict ?: return
        conflicts++
        for (v in boolVars) bump(act, lc, v)
        for (v in intVars) bump(act, lc, numBoolVars + v)
        bumpInc /= ACTIVITY_DECAY
        if (bumpInc > RESCALE_THRESHOLD) {
            for (i in act.indices) act[i] /= RESCALE_THRESHOLD
            maxActivity /= RESCALE_THRESHOLD
            bumpInc /= RESCALE_THRESHOLD
        }
    }

    private fun bump(act: DoubleArray, lc: LongArray, slot: Int) {
        act[slot] += bumpInc
        if (act[slot] > maxActivity) maxActivity = act[slot]
        lc[slot] = conflicts
    }

    override fun onConflict(varRef: VarRef) {
        conflictSincePick = true
    }

    override fun onConflict(varRef: VarRef, unsat: PropagationResult.Unsat) {
        conflictSincePick = true
        bumpConflict(unsat.conflictBools, unsat.conflictInts)
    }

    override fun onPropagation(implied: PropagationResult.Implied) = Unit

    override fun onRestart() {
        pendingFeatures = null
        conflictSincePick = false
    }

    override fun onSolution(snapshot: Sample) = Unit

    /** Feature-vector size and the [linUcb] factory. */
    companion object {
        /** Number of per-variable features in the LinUCB context vector. */
        const val FEATURE_SIZE: Int = 7
        private const val PROP_SQUASH = 16.0
        private const val ACTIVITY_DECAY = 0.95
        private const val RESCALE_THRESHOLD = 1e100

        /**
         * Build a LinUCB-backed variable heuristic. [exploration] scales the upper-confidence term,
         * [priorVariance] the Bayesian prior, and [scoreCap] the per-decision candidate budget.
         */
        fun linUcb(
            seed: Long = 0L,
            exploration: Double = 1.0,
            priorVariance: Double = 1.0,
            scoreCap: Int = 64,
        ): RegressionVariableSelector {
            val newBandit = {
                val regression = BayesianRegression(FEATURE_SIZE, priorVariance)
                val spec = RegressionContextualSpec(1, regression, LinUcb, exploration, regression)
                spec.materialize(Random(seed), Concurrency.None)
            }
            return RegressionVariableSelector(newBandit, scoreCap)
        }
    }
}
