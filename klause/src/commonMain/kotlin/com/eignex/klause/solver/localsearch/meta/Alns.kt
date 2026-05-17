package com.eignex.klause.solver.localsearch.meta

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.LocalSearchParams
import kotlin.random.Random

/**
 * Adaptive Large Neighborhood Search meta-optimizer on top of an arbitrary
 * [Optimizer] over [LocalSearchParams]. Each iteration:
 *
 *   1. Pick a destroy operator from [destroyOperators] via [destroyBandit].
 *   2. Pick a repair operator from [repairOperators] via [repairBandit].
 *   3. Free `destroyFraction * totalVars` variables; pin the rest at incumbent values
 *      via [Assumptions].
 *   4. Hand the pinned problem to the chosen repair operator (typically calls
 *      `inner.minimize` with the pin set; alternative operators can vary the budget,
 *      restart cadence, or even the underlying solver).
 *   5. Reward both bandits: [newBestReward] / [acceptedReward] / [rejectedReward]
 *      depending on whether the repaired solution beats the global best, replaces the
 *      incumbent under [acceptance], or is rejected. Both bandits learn independently;
 *      the joint (destroy, repair) pair distribution emerges from their interaction.
 *
 * Compared to the [com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart] —
 * which lives inside the LS engine and re-anchors the assignment between flips — ALNS
 * is an *outer* loop: it repeatedly calls a repair operator with shrinking assumption
 * sets and lets the bandits learn which destroy/repair combo exposes the most rewarding
 * neighbourhood for this problem. Composes naturally with Session + assumption primitives.
 *
 * Specialised to [LocalSearchParams] so we can override `maxFlips` per iteration; the
 * generic-over-`P` version would need a `SolverParams.withMaxFlips` extension point.
 */
class Alns(
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
    val destroyBandit: RouletteWheelBandit = RouletteWheelBandit(destroyOperators.size),
    val repairBandit: RouletteWheelBandit = RouletteWheelBandit(repairOperators.size),
    val rng: Random = Random.Default,
) : Optimizer<LocalSearchParams> {

    init {
        require(destroyOperators.isNotEmpty()) { "Need at least one destroy operator" }
        require(repairOperators.isNotEmpty()) { "Need at least one repair operator" }
        require(destroyOperators.size == destroyBandit.numOperators) {
            "destroyBandit operator count ${destroyBandit.numOperators} doesn't match destroyOperators ${destroyOperators.size}"
        }
        require(repairOperators.size == repairBandit.numOperators) {
            "repairBandit operator count ${repairBandit.numOperators} doesn't match repairOperators ${repairOperators.size}"
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

    override fun minimize(objective: Objective, params: LocalSearchParams): Sample? {
        _iterationLog.clear()
        // Initial solve to get an incumbent. Pass through the caller's full budget so the
        // first feasibility / optimisation pass isn't artificially truncated.
        var bestSample = inner.minimize(objective, params) ?: return null
        var bestObj = objective.evaluate(bestSample)
        var incumbent = bestSample
        var incumbentObj = bestObj

        val perIterParams = params.copy(
            maxFlips = flipsPerIteration,
            // Each iteration's RNG seed varies via the bandit's RNG; explicit null lets the
            // inner solver draw its own per-call seed.
            randomSeed = null,
        )

        for (iter in 0 until maxIterations) {
            if (params.cancellation()) break
            val destroyIdx = destroyBandit.pick(rng)
            val repairIdx = repairBandit.pick(rng)
            val freed = destroyOperators[destroyIdx]
                .destroy(rng, inner.problem, incumbent, objective, destroyFraction)
            if (freed.isEmpty) {
                destroyBandit.reward(destroyIdx, rejectedReward)
                repairBandit.reward(repairIdx, rejectedReward)
                destroyBandit.advance(); repairBandit.advance()
                continue
            }

            val pinAssumptions = buildPin(inner.problem, incumbent, freed)
            val context = RepairContext(inner, perIterParams, objective, pinAssumptions, incumbent, freed)
            val repaired = repairOperators[repairIdx].repair(context)
            if (repaired == null) {
                destroyBandit.reward(destroyIdx, rejectedReward)
                repairBandit.reward(repairIdx, rejectedReward)
                destroyBandit.advance(); repairBandit.advance()
                continue
            }
            val repairedObj = objective.evaluate(repaired)

            val isNewBest = repairedObj < bestObj
            val accept = isNewBest || acceptance.accept(repairedObj, incumbentObj, rng)
            val reward = when {
                isNewBest -> newBestReward
                accept -> acceptedReward
                else -> rejectedReward
            }
            destroyBandit.reward(destroyIdx, reward)
            repairBandit.reward(repairIdx, reward)
            _iterationLog.add(IterationRecord(destroyIdx, repairIdx, freed.bools.size + freed.ints.size,
                incumbentObj, repairedObj, accept, isNewBest))

            if (isNewBest) { bestSample = repaired; bestObj = repairedObj }
            if (accept) { incumbent = repaired; incumbentObj = repairedObj }
            destroyBandit.advance(); repairBandit.advance()
        }
        return bestSample
    }

    /** Build an [Assumptions] pinning every variable *not* in [freed] to its incumbent value. */
    private fun buildPin(problem: Problem, incumbent: Sample, freed: FreedVars): Assumptions {
        val freedBoolSet = freed.bools.toHashSet()
        val freedIntSet = freed.ints.toHashSet()
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
