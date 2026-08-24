package com.eignex.klause.backtrack.selector

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.values
import com.eignex.kumulant.bandit.contextual.LinearRegressionSpec
import com.eignex.kumulant.bandit.contextual.RegressionContextualBandit
import com.eignex.kumulant.bandit.contextual.RegressionContextualSpec
import com.eignex.kumulant.bandit.materialize
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.math.DenseVector
import com.eignex.kumulant.stat.regression.glm.LinUcb
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Contextual-bandit variable heuristic: a kumulant [RegressionContextualBandit]
 * (LinUCB over a Bayesian linear-regression posterior) learns, **per session**, a value function
 * over per-variable branching features and picks the unassigned variable with the highest LinUCB
 * score. It is a learned generalisation of the hand-tuned heuristics: where [Vsids] attributes
 * activity to variables and [DomWdeg] weight to constraints, this fits a linear model to features
 * that include both signals and lets the posterior weight them for the instance at hand.
 *
 * **Soundness:** the bandit only chooses *which* unassigned variable to branch on next; every such
 * choice is sound, so search stays complete and correct regardless of what the model learns. Only
 * efficiency depends on the model.
 *
 * **Cost guard:** scoring every candidate each decision is O(candidates · featureWork), far heavier
 * than VSIDS's O(log n) heap pick. On wide instances that would dominate the search, so at most
 * [scoreCap] candidates are scored — when more are unassigned, the smallest-domain ones (the
 * fail-first prior) are taken first and the rest ignored this decision.
 *
 * **Reward** is attributed at the *next* decision (the outcome of the previous branch is known by
 * then): a branch that led straight to a conflict scores 1.0 (informative fail-first pruning),
 * otherwise the reward is the propagation it triggered, squashed into [0,1]. A single arm (index 0)
 * carries the shared regression; the per-candidate feature vector is the context.
 */
class RegressionVariableSelector private constructor(
    private val newBandit: () -> RegressionContextualBandit<*>,
    private val scoreCap: Int,
) : VariableSelector {

    private val bandit: RegressionContextualBandit<*> = newBandit()

    /** A fresh selector with the same config and a new, unlearned bandit — so reuse across solves
     *  starts each search from the documented per-session state, never a prior problem's model. */
    override fun fresh() = RegressionVariableSelector(newBandit, scoreCap)

    // Pending attribution for the previous decision (rewarded at the next pick, once its outcome
    // is observable). Null between runs / before the first decision.
    private var pendingFeatures: DoubleArray? = null
    private var pendingPropCount: Long = 0L
    private var conflictSincePick: Boolean = false

    // Self-maintained VSIDS-style activity + last-conflict step (the engine exposes neither to a
    // heuristic). Slot-indexed: bool var v → v, int var v → numBoolVars + v. Allocated lazily on
    // the first pick (problem size isn't known at construction). With these as features the linear
    // model can express VSIDS (weight on activity) and LastConflict (weight on recency) as well as
    // fail-first, so it can learn the right family per instance.
    private var activity: DoubleArray? = null
    private var lastConflict: LongArray? = null
    private var numBoolVars: Int = 0
    private var conflicts: Long = 0L
    private var bumpInc: Double = 1.0
    private var maxActivity: Double = 1.0

    // Instance-relative feature scales, computed once from the problem in ensureState (so the
    // unbounded structural features land in ~[0,1] off the instance's own maxima, not magic
    // constants). degree ÷ max degree, ln(domSize) ÷ ln(max domain), depth ÷ variable count.
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
            val score = bandit.evaluate(0, DenseVector.of(f))
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
            // Squash propagation yield into [0,1): 0 → 0, large → ~1. (delta / (delta + k))
            (delta / (delta + PROP_SQUASH)).coerceIn(0.0, 1.0)
        }
        bandit.update(0, DenseVector.of(f), reward, 1.0)
        pendingFeatures = null
    }

    /** A scored candidate: its [ref] and current domain size (the cheap fail-first key). */
    private class Candidate(val ref: VarRef, val domSize: Int)

    private fun collectCandidates(session: PropagationSession): List<Candidate> {
        val problem = session.problem
        val all = ArrayList<Candidate>()
        for (v in 0 until problem.numBoolVars) {
            if (session.boolValue(v) == null) all.add(Candidate(VarRef.Bool(v), 2))
        }
        for (v in 0 until problem.numIntVars) {
            val size = session.intDomain(v).values.size
            if (size > 1) all.add(Candidate(VarRef.IntVar(v), size))
        }
        if (all.size <= scoreCap) return all
        // Too many to score; keep the scoreCap smallest-domain candidates (fail-first prior).
        return all.sortedBy { it.domSize }.subList(0, scoreCap)
    }

    private fun features(session: PropagationSession, c: Candidate): DoubleArray {
        val problem = session.problem
        val isBool = c.ref is VarRef.Bool
        val degree = if (isBool) {
            problem.boolOccurrences[c.ref.varId].size
        } else {
            problem.intOccurrences[c.ref.varId].size
        }
        val slot = if (isBool) c.ref.varId else numBoolVars + c.ref.varId
        val act = checkNotNull(activity)[slot] / maxActivity // VSIDS-style decayed activity, ∈ [0,1]
        val lc = checkNotNull(lastConflict)[slot]
        val recency = if (lc < 0L) 0.0 else 1.0 / (1.0 + (conflicts - lc)) // LastConflict signal
        return doubleArrayOf(
            1.0 / c.domSize, // fail-first: small domain → large value (already ∈ (0, 0.5])
            ln((c.domSize + 1).toDouble()) / lnDomScale, // domain scale ÷ the instance's max
            min(degree / degreeScale, 1.0), // constraint degree ÷ the instance's max degree
            min(session.decisionLevel / depthScale, 1.0), // depth ÷ var count (max possible depth)
            if (isBool) 1.0 else 0.0, // variable kind
            act, // conflict activity — weight→1 here recovers VSIDS branching
            recency, // last-conflict recency — weight→1 here recovers LastConflict branching
        )
    }

    /** Lazily size the activity/last-conflict arrays and compute the instance-relative feature
     *  scales (max degree, max domain, variable count) on the first pick — problem size isn't known
     *  at construction. */
    private fun ensureState(session: PropagationSession) {
        if (activity != null) return
        val problem = session.problem
        numBoolVars = problem.numBoolVars
        val n = numBoolVars + problem.numIntVars
        activity = DoubleArray(n)
        lastConflict = LongArray(n) { -1L }
        var maxDegree = 1
        for (v in 0 until problem.numBoolVars) maxDegree = maxOf(maxDegree, problem.boolOccurrences[v].size)
        for (v in 0 until problem.numIntVars) maxDegree = maxOf(maxDegree, problem.intOccurrences[v].size)
        degreeScale = maxDegree.toDouble()
        var maxDom = 2
        for (v in 0 until problem.numIntVars) maxDom = maxOf(maxDom, problem.intDomains[v].values.size)
        lnDomScale = ln((maxDom + 1).toDouble())
        depthScale = maxOf(1, n).toDouble()
    }

    /** VSIDS-style bump of the variables involved in a conflict, with the inc-grows decay trick
     *  (no O(n) per-conflict pass; rescale when the increment overflows). Also records the conflict
     *  step for each involved var (the LastConflict recency signal). */
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
        // Trail unwinds to root; drop any half-attributed decision.
        pendingFeatures = null
        conflictSincePick = false
    }

    override fun onSolution(snapshot: Sample) = Unit

    /** Feature-vector size and the [linUcb] factory. */
    companion object {
        /** Number of per-variable features the LinUCB context vector carries (see [features]):
         *  fail-first (1/domSize), domain scale, degree, depth, kind, conflict activity, recency. */
        const val FEATURE_SIZE: Int = 7
        private const val PROP_SQUASH = 16.0
        private const val ACTIVITY_DECAY = 0.95 // VSIDS-style: increment grows by 1/decay per conflict
        private const val RESCALE_THRESHOLD = 1e100

        /**
         * Build a LinUCB-backed variable heuristic. [exploration] scales the LinUCB upper-confidence
         * term (more = more exploratory), [priorVariance] the Bayesian prior, [scoreCap] the
         * per-decision candidate budget (see the cost guard above).
         */
        fun linUcb(
            seed: Long = 0L,
            exploration: Double = 1.0,
            priorVariance: Double = 1.0,
            scoreCap: Int = 64,
        ): RegressionVariableSelector {
            val newBandit = {
                val regression = LinearRegressionSpec.Bayesian(FEATURE_SIZE, priorVariance)
                val spec = RegressionContextualSpec(1, regression, LinUcb, exploration, regression)
                spec.materialize(Random(seed), Concurrency.None)
            }
            return RegressionVariableSelector(newBandit, scoreCap)
        }
    }
}
