package com.eignex.klause.meta.alns

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSession
import com.eignex.klause.localsearch.PooledSolutionImporter
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
 *   3. Free a randomized fraction of the variables; pin the rest at incumbent values
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
    /** Optional factory building the acceptance criterion from the initial incumbent's objective. A
     *  simulated-annealing temperature is only meaningful relative to the objective's magnitude, so this
     *  lets a caller scale the starting temperature to `f(initial)` (a fixed temperature would be inert on
     *  a large objective and reckless on a tiny one). When null, [acceptance] is used as given. */
    val acceptanceFor: ((initialObjective: Double) -> AcceptanceCriterion)? = null,
    /** Bounds on the destroy size, as a fraction of the total variables. Each iteration draws the fraction
     *  uniformly from `[minDestroyFraction, maxDestroyFraction]` (fixed when they are equal) — the textbook
     *  ALNS randomized degree of destruction, alternating small refining neighbourhoods with large
     *  diversifying ones so no single fixed size dominates the search. */
    val minDestroyFraction: Double = 0.1,
    val maxDestroyFraction: Double = 0.4,
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
    /** Optional backtrack LCG+LP engine and base params for CP repair ([BacktrackRepair], #644). When
     *  set, a repair operator can solve each freed fragment with full propagation + clause learning +
     *  LP bounding under the pin assumptions. Null on a pure-LS ALNS. */
    val backtrack: Optimizer<BacktrackParams>? = null,
    val backtrackParams: BacktrackParams? = null,
    /** Sink for each improving incumbent this run accepts — its [Sample] and objective. A portfolio folds
     *  these into the shared solution pool so peer arms (backtrack or LS) can warm-start from them. Null
     *  disables publishing. */
    val improvedSolutionSink: ((Sample, Double) -> Unit)? = null,
    /** Supplier of the best [Sample] any arm has published — the read counterpart of [improvedSolutionSink].
     *  Before each iteration ALNS adopts a not-yet-seen pooled solution that beats its own incumbent, so the
     *  next neighbourhood is destroyed from the globally-best assignment. Identity-gated; skipped when the
     *  run carries assumption pins. Null disables it. */
    val pooledSolutionSupplier: (() -> Sample?)? = null,
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
        require(minDestroyFraction in 0.0..1.0) { "minDestroyFraction must be in [0, 1], got $minDestroyFraction" }
        require(maxDestroyFraction in minDestroyFraction..1.0) {
            "maxDestroyFraction ($maxDestroyFraction) must be in [minDestroyFraction, 1]"
        }
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
        // Initial incumbent for the destroy/repair loop (LS-first, backtrack-fallback — see below).
        val initialResult = bootstrapIncumbent(objective, params)
        val initialSample = initialResult.assignment ?: return initialResult
        var bestSample: Sample = initialSample
        var bestObj = scoring.evaluate(bestSample)
        var incumbent = bestSample
        var incumbentObj = bestObj
        improvedSolutionSink?.invoke(bestSample, bestObj)
        // Build the acceptance policy once the initial objective is known, so a simulated-annealing
        // temperature can be scaled to the problem (see [acceptanceFor]); else use the fixed policy.
        val acceptancePolicy = acceptanceFor?.invoke(bestObj) ?: acceptance
        // Cross-engine solution flow (#644): adopt a fresher-and-better pooled assignment as the incumbent
        // before destroying, so the next neighbourhood searches around the globally-best assignment.
        val pooledImporter = PooledSolutionImporter(
            supplier = pooledSolutionSupplier,
            enabled = params.assumptions.isEmpty,
            evaluate = { scoring.evaluate(it) },
        )

        val perIterParams = params.copy(
            maxFlips = flipsPerIteration,
            // Each iteration's RNG seed varies via the bandit's RNG; explicit null lets the
            // inner solver draw its own per-call seed.
            randomSeed = null,
        )
        // Persistent CP-repair handle (#644): one session + LP reused across fragments, re-seeded per
        // neighbourhood, so learned clauses and the LP warm start carry between repairs. Only when a
        // backtrack engine is supplied; closed at the end of the run.
        val repairSearch = (backtrack as? BacktrackSolver)?.let { bt ->
            backtrackParams?.let { bp -> bt.openRepair(objective, bp) }
        }

        var iter = 0
        while (iter < maxIterations) {
            if (params.cancellation()) break
            pooledImporter.poll(bestObj)?.let { (sample, obj) ->
                bestSample = sample
                bestObj = obj
                incumbent = sample
                incumbentObj = obj
            }
            val destroyIdx = destroyBandit.choose()
            val repairIdx = repairBandit.choose()
            // Randomized degree of destruction: a fresh fraction each iteration (textbook ALNS).
            val destroyFraction = if (maxDestroyFraction > minDestroyFraction) {
                minDestroyFraction + rng.nextDouble() * (maxDestroyFraction - minDestroyFraction)
            } else {
                minDestroyFraction
            }
            val freed = destroyOperators[destroyIdx]
                .destroy(rng, inner.problem, incumbent, objective, destroyFraction)
            if (freed.isEmpty) {
                destroyBandit.update(destroyIdx, rejectedReward)
                repairBandit.update(repairIdx, rejectedReward)
                iter++
                continue
            }

            val pinAssumptions = buildPin(inner.problem, incumbent, freed)
            val context = RepairContext(
                inner, perIterParams, objective, pinAssumptions, incumbent, freed, rng, session,
                backtrack = backtrack, backtrackParams = backtrackParams,
                repairSearch = repairSearch, bestObjective = bestObj,
            )
            val repaired = repairOperators[repairIdx].repair(context)
            if (repaired == null) {
                destroyBandit.update(destroyIdx, rejectedReward)
                repairBandit.update(repairIdx, rejectedReward)
                iter++
                continue
            }
            val repairedObj = scoring.evaluate(repaired)

            val isNewBest = repairedObj < bestObj
            val accept = isNewBest || acceptancePolicy.accept(repairedObj, incumbentObj, rng)
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
                improvedSolutionSink?.invoke(repaired, repairedObj)
            }
            if (accept) {
                incumbent = repaired
                incumbentObj = repairedObj
            }
            iter++
        }
        repairSearch?.close()
        // ALNS is incomplete — every successful run returns BestFound, never Optimal.
        return MinimizeResult.BestFound(
            sample = bestSample,
            objective = bestObj,
            reason = TerminationReason.BudgetExhausted,
        )
    }

    /**
     * The first incumbent for the destroy/repair loop. For a hybrid ALNS (a backtrack engine is present)
     * seed it with a *budget-bounded complete solve*: complete search reaches a first feasible fast on both
     * easy and feasibility-tight problems — where local search flails and leaves ALNS with no incumbent at
     * all (bench-mined) — and hands over a CP-quality start. It is capped to a fraction of the run's budget
     * (via [com.eignex.klause.solver.Cancellation.shorten]) so destroy/repair keeps the rest, with
     * [BOOTSTRAP_DECISIONS] as the safeguard for a budget-less (deadline-free) run. A pure-LS ALNS (no
     * backtrack engine), or a fragment the bootstrap can't seed, falls back to local search.
     */
    private fun bootstrapIncumbent(objective: LinearObjective, params: LocalSearchParams): MinimizeResult {
        val engine = backtrack
        if (engine != null) {
            val base = backtrackParams ?: BacktrackParams()
            val cpResult = engine.minimize(
                objective,
                base.copy(maxDecisions = BOOTSTRAP_DECISIONS)
                    .withCancellation(params.cancellation.shorten(BT_BOOTSTRAP_FRACTION)),
            )
            if (cpResult.assignment != null) return cpResult
        }
        return session?.minimize(objective, params) ?: inner.minimize(objective, params)
    }

    /**
     * Pin every variable *not* in [freed] to its incumbent value, written straight into the sorted
     * primitive arrays [Assumptions] holds — no per-iteration boxing map. The complement is still most
     * of the problem, but the reused repair session re-seeds it by diff
     * ([com.eignex.klause.propagation.PropagationSession.reseedFrom]),
     * so only the pins that actually changed between fragments cost propagation.
     */
    private fun buildPin(problem: Problem, incumbent: Sample, freed: FreedVars): Assumptions {
        val freedBoolSet = IntHashSet().apply { for (b in freed.bools) add(b) }
        val freedIntSet = IntHashSet().apply { for (i in freed.ints) add(i) }
        val boolKeys = IntArray(problem.numBoolVars - freedBoolSet.size)
        val boolValues = BooleanArray(boolKeys.size)
        var bi = 0
        for (b in 0 until problem.numBoolVars) {
            if (b in freedBoolSet) continue
            boolKeys[bi] = b
            boolValues[bi] = incumbent.bools[b]
            bi++
        }
        val intKeys = IntArray(problem.numIntVars - freedIntSet.size)
        val intValues = LongArray(intKeys.size)
        var ii = 0
        for (i in 0 until problem.numIntVars) {
            if (i in freedIntSet) continue
            intKeys[ii] = i
            intValues[ii] = incumbent.ints[i]
            ii++
        }
        // Keys emerge ascending from the 0..n scan — exactly the sorted order the array constructor wants.
        return Assumptions(boolKeys, boolValues, intKeys, intValues)
    }

    private companion object {
        /** Budget slice ([com.eignex.klause.solver.Cancellation.shorten]) the complete-engine bootstrap may
         *  use before yielding to destroy/repair. Calibration knob (#5). */
        const val BT_BOOTSTRAP_FRACTION = 0.5

        /** Decision-count safeguard on the complete-engine bootstrap for a deadline-free run, where
         *  `shorten` cannot bound by time. Big enough to reach a first feasible on tight problems. */
        const val BOOTSTRAP_DECISIONS = 50_000L
    }
}
