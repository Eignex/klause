package com.eignex.klause.meta.alns

import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.Objective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.IntHashSet
import com.eignex.kumulant.bandit.UnivariateBandit
import com.eignex.kumulant.bandit.univariate.RouletteWheelBandit
import kotlin.random.Random

/**
 * Adaptive Large Neighborhood Search meta-optimizer on top of an arbitrary
 * [Optimizer] over [LocalSearchParams]. Each iteration:
 *
 *   1. Pick a destroy operator from [destroyOperators] via [destroyBandit] (kumulant
 *      [UnivariateBandit] — defaults to [RouletteWheelBandit]; swap in
 *      `MultiArmedBandit(BetaBernoulliTS())` for Thompson sampling or any other
 *      kumulant policy).
 *   2. Pick a repair operator from [repairOperators] via [repairBandit].
 *   3. Free `destroyFraction * totalVars` variables; pin the rest at incumbent values
 *      via [Assumptions].
 *   4. Hand the pinned problem to the chosen repair operator (typically calls
 *      `inner.minimize` with the pin set; alternative operators can vary the budget,
 *      restart cadence, or even the underlying solver).
 *   5. Reward both bandits: [newBestReward] / [acceptedReward] / [rejectedReward]
 *      depending on whether the repaired solution beats the global best, replaces the
 *      incumbent under [acceptance], or is rejected. Reward magnitudes are passed
 *      through to the bandit unchanged — callers using `BetaBernoulliTS` (which
 *      expects soft Bernoulli probabilities) should configure rewards in `[0, 1]`;
 *      the default values target `RouletteWheelBandit`'s weight-update scheme. Both
 *      bandits learn independently; the joint (destroy, repair) pair distribution
 *      emerges from their interaction.
 *
 * Compared to the [com.eignex.klause.localsearch.IteratedLocalSearchRestart] —
 * which lives inside the LS engine and re-anchors the assignment between flips — ALNS
 * is an *outer* loop: it repeatedly calls a repair operator with shrinking assumption
 * sets and lets the bandits learn which destroy/repair combo exposes the most rewarding
 * neighbourhood for this problem. Composes naturally with Session + assumption primitives.
 *
 * Specialised to [LocalSearchParams] so we can override `maxFlips` per iteration; the
 * generic-over-`P` version would need a `SolverParams.withMaxFlips` extension point.
 */
internal class Alns(
    val inner: Optimizer<LocalSearchParams>,
    val destroyOperators: List<DestroyOperator> = DestroyOperator.Defaults,
    val repairOperators: List<RepairOperator> = RepairOperator.Defaults,
    val acceptance: AcceptanceCriterion = AcceptanceCriterion.Improving,
    val destroyFraction: Double = 0.25,
    val maxIterations: Int = 50,
    val flipsPerIteration: Long = 1_000L,
    val newBestReward: Double = 3.0,
    val acceptedReward: Double = 1.0,
    val rejectedReward: Double = 0.0,
    /** Single RNG driving destroy-operator randomization, acceptance criteria, repair
     *  contexts, and the default-constructed bandits. Pass `Random(seed)` for a fully
     *  reproducible ALNS run from one seed — every randomized component derives its
     *  draws from this stream. Users supplying custom bandits are responsible for
     *  those bandits' RNGs (kumulant bandits each take a `random: Random` parameter). */
    val rng: Random = Random.Default,
    val destroyBandit: UnivariateBandit = RouletteWheelBandit(destroyOperators.size, random = rng),
    val repairBandit: UnivariateBandit = RouletteWheelBandit(repairOperators.size, random = rng),
    /** Optional session for cross-iteration state. When provided, [InnerLsRepair] (and
     *  any other repair operator that reads `context.session`) routes through it so
     *  DDFW factor weights and per-variable activity recency survive across iterations.
     *  Required to make `DestroyOperator.activityBiased(session)` useful — without a
     *  session it falls back to random. Pass `solver.session() as LocalSearchSession`. */
    val session: LocalSearchSession? = null,
) : Optimizer<LocalSearchParams> {

    init {
        require(destroyOperators.isNotEmpty()) { "Need at least one destroy operator" }
        require(repairOperators.isNotEmpty()) { "Need at least one repair operator" }
        require(destroyOperators.size == destroyBandit.nbrArms) {
            "destroyBandit arm count ${destroyBandit.nbrArms} doesn't match destroyOperators ${destroyOperators.size}"
        }
        require(repairOperators.size == repairBandit.nbrArms) {
            "repairBandit arm count ${repairBandit.nbrArms} doesn't match repairOperators ${repairOperators.size}"
        }
        require(destroyFraction in 0.0..1.0) { "destroyFraction must be in [0, 1], got $destroyFraction" }
    }

    override val problem: Problem get() = inner.problem
    override fun solve(params: LocalSearchParams) = inner.solve(params)
    override fun samples(params: LocalSearchParams) = inner.samples(params)
    override fun enumerate(params: LocalSearchParams) = inner.enumerate(params)

    private val _iterationLog: MutableList<IterationRecord> = mutableListOf()

    /** History snapshot exposed for tests / debugging; not part of the Optimizer contract.
     *  Read-only view — ALNS appends internally; callers may iterate but not mutate. */
    val iterationLog: List<IterationRecord> get() = _iterationLog

    data class IterationRecord(
        val destroyIdx: Int,
        val repairIdx: Int,
        val freedCount: Int,
        val incumbentObjective: Double,
        val newObjective: Double,
        val accepted: Boolean,
        val newBest: Boolean,
    )

    override fun minimize(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
        _iterationLog.clear()
        // Score with the caller's gradient view when one is supplied (it agrees with the linear
        // objective at every feasible point); the inner solves resolve the same view from params.
        val scoring: Objective = params.lsObjective ?: objective
        // Initial solve to get an incumbent. Route through the session when present so the
        // first solve seeds DDFW weights / recency for later activity-biased destroys.
        val initialResult = session?.minimize(objective, params) ?: inner.minimize(objective, params)
        val initialSample = initialResult.assignment ?: return initialResult
        var bestSample: Sample = initialSample
        var bestObj = scoring.evaluate(bestSample)
        var incumbent = bestSample
        var incumbentObj = bestObj

        val perIterParams = params.copy(
            maxFlips = flipsPerIteration,
            // Each iteration's RNG seed varies via the bandit's RNG; explicit null lets the
            // inner solver draw its own per-call seed.
            randomSeed = null,
        )

        var iter = 0
        while (iter < maxIterations) {
            if (params.cancellation()) break
            val destroyIdx = destroyBandit.choose()
            val repairIdx = repairBandit.choose()
            val freed = destroyOperators[destroyIdx]
                .destroy(rng, inner.problem, incumbent, objective, destroyFraction)
            if (freed.isEmpty) {
                destroyBandit.update(destroyIdx, rejectedReward)
                repairBandit.update(repairIdx, rejectedReward)
                iter++
                continue
            }

            val pinAssumptions = buildPin(inner.problem, incumbent, freed)
            val context = RepairContext(inner, perIterParams, objective, pinAssumptions, incumbent, freed, rng, session)
            val repaired = repairOperators[repairIdx].repair(context)
            if (repaired == null) {
                destroyBandit.update(destroyIdx, rejectedReward)
                repairBandit.update(repairIdx, rejectedReward)
                iter++
                continue
            }
            val repairedObj = scoring.evaluate(repaired)

            val isNewBest = repairedObj < bestObj
            val accept = isNewBest || acceptance.accept(repairedObj, incumbentObj, rng)
            val reward = when {
                isNewBest -> newBestReward
                accept -> acceptedReward
                else -> rejectedReward
            }
            destroyBandit.update(destroyIdx, reward)
            repairBandit.update(repairIdx, reward)
            _iterationLog.add(
                IterationRecord(
                    destroyIdx,
                    repairIdx,
                    freed.bools.size + freed.ints.size,
                    incumbentObj,
                    repairedObj,
                    accept,
                    isNewBest,
                ),
            )

            if (isNewBest) {
                bestSample = repaired
                bestObj = repairedObj
            }
            if (accept) {
                incumbent = repaired
                incumbentObj = repairedObj
            }
            iter++
        }
        // ALNS is incomplete — every successful run returns BestFound, never Optimal.
        return MinimizeResult.BestFound(
            sample = bestSample,
            objective = bestObj,
            reason = TerminationReason.BudgetExhausted,
        )
    }

    /** Build an [Assumptions] pinning every variable *not* in [freed] to its incumbent value. */
    private fun buildPin(problem: Problem, incumbent: Sample, freed: FreedVars): Assumptions {
        val freedBoolSet = IntHashSet().apply { for (b in freed.bools) add(b) }
        val freedIntSet = IntHashSet().apply { for (i in freed.ints) add(i) }
        val pinnedBools = HashMap<Int, Boolean>(problem.numBoolVars)
        val pinnedInts = HashMap<Int, Int>(problem.numIntVars)
        for (b in 0 until problem.numBoolVars) {
            if (b !in freedBoolSet) pinnedBools[b] = incumbent.bools[b]
        }
        for (i in 0 until problem.numIntVars) {
            if (i !in freedIntSet) pinnedInts[i] = incumbent.ints[i]
        }
        return Assumptions(pinnedBools, pinnedInts)
    }
}
