package com.eignex.klause.bench.tune

import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import kotlin.math.abs

/**
 * The Bayesian-optimization ask-tell loop over a [ConfigSpace] (task #24). Each round asks a [Tuner]
 * for a batch of config points, evaluates every one in-process ([InProcessEval]) on the whole instance
 * set, scores it by a reference-normalised gap-to-optimum reward (#26), and tells the mean reward back.
 * After the trial budget, a greedy set-cover over the per-instance winners
 * ([ArmCalibration.scoreWinnerSets]) selects a **diverse palette** — the configs that together win the
 * most instances, not the single best-on-average one.
 *
 * COP-only: [InProcessEval] needs an objective (so pass `kind=cop` instances). The loop depends only on
 * the [Tuner] seam, so the optimizer backend (Vizier via [VizierTuner], or [RandomTuner]) is swappable.
 */
internal object BoTuning {
    /** Configs whose per-instance rewards tie within this are co-winners (shared set-cover credit). */
    private const val TIE_EPSILON = 1e-9

    /** Reward for a feasible instance with no committed reference optimum — feasibility credit only,
     *  below any gap-scored reward so referenced instances drive the ranking. */
    private const val UNREFERENCED_FEASIBLE_REWARD = 0.5

    /**
     * The tuning outcome: [report] ranks the evaluated configs into a diverse palette (greedy set-cover
     * over per-instance winners), and [configs] maps each config's label to its coerced assignment so a
     * caller can materialize the winning recipes (task #25).
     */
    data class Result(val report: ArmCalibration.Report, val configs: Map<String, Map<String, Any>>)

    /** Tune the local-search config space with [tuner] over the COP [instances]. */
    fun tuneLs(
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        studyId: String = "ls-bo",
    ): Result = tune(
        LocalSearchConfigSpace,
        LocalSearchConfigSpace::toRecipe,
        InProcessEval::evalLs,
        instances, tuner, trials, batch, budgetMs, seed, studyId,
    )

    /** Tune the backtrack config space with [tuner] over the COP [instances]. */
    fun tuneBt(
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        studyId: String = "bt-bo",
    ): Result = tune(
        BacktrackConfigSpace,
        BacktrackConfigSpace::toParams,
        InProcessEval::evalBt,
        instances, tuner, trials, batch, budgetMs, seed, studyId,
    )

    /**
     * The engine-agnostic loop: [decode] turns a coerced assignment into the engine's config [T] and
     * [eval] runs it on one instance. Evaluates each of [trials] suggested points (asked [batch] at a
     * time) on every instance at [budgetMs]/[seed], tells the mean reward back, and returns the palette.
     */
    fun <T> tune(
        space: ConfigSpace,
        decode: (Map<String, Any>) -> T,
        eval: (ResolvedProblem, T, Long, Long) -> EvalResult,
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        studyId: String,
    ): Result {
        require(instances.isNotEmpty()) { "tune needs at least one instance" }
        require(trials >= 1 && batch >= 1) { "trials and batch must be >= 1" }
        val references = ReferenceStore.load()
        // label -> per-instance reward (dedup identical config points across suggestions).
        val rewardsByConfig = LinkedHashMap<String, DoubleArray>()
        val configs = LinkedHashMap<String, Map<String, Any>>()

        tuner.openStudy(space, maximize = true, studyId).use { study ->
            var evaluated = 0
            while (evaluated < trials) {
                val ask = minOf(batch, trials - evaluated)
                for (suggestion in study.suggest(ask)) {
                    val assignment = space.coerce(suggestion.values)
                    val decoded = decode(assignment)
                    val perInstance = DoubleArray(instances.size) { i ->
                        reward(references, instances[i], eval(instances[i], decoded, budgetMs, seed))
                    }
                    study.complete(suggestion, perInstance.average())
                    val label = labelOf(assignment)
                    if (rewardsByConfig.putIfAbsent(label, perInstance) == null) configs[label] = assignment
                }
                evaluated += ask
            }
        }

        val labels = rewardsByConfig.keys.toList()
        // A config wins an instance if its reward ties the best on it; instances no config scored above
        // zero on (all infeasible) are non-discriminating and drop out of the set-cover.
        val won = instances.indices.mapNotNull { i ->
            val best = labels.maxOf { rewardsByConfig.getValue(it)[i] }
            if (best <= 0.0) {
                null
            } else {
                labels.filterTo(HashSet()) { rewardsByConfig.getValue(it)[i] >= best - TIE_EPSILON }
            }
        }
        return Result(ArmCalibration.scoreWinnerSets(labels, won, instances.size), configs)
    }

    /** Stable per-config label: the coerced assignment sorted by key, so identical points collapse. */
    private fun labelOf(assignment: Map<String, Any>): String =
        assignment.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    /**
     * Reference-normalised gap-to-optimum reward in `[0, 1]` (higher is better). Infeasible → 0. A
     * feasible result with a committed reference optimum is scored `1 - gap/denom` where `gap` is how
     * far the found objective sits above the optimum (both in the minimised orientation) and `denom`
     * normalises across instances of wildly different scale; with no committed optimum it earns only
     * [UNREFERENCED_FEASIBLE_REWARD]. This makes the mean-over-instances objective comparable, which is
     * what lets one Vizier metric drive the search across a heterogeneous corpus.
     */
    private fun reward(
        references: Map<Pair<String, String>, ReferenceEntry>,
        entry: ResolvedProblem,
        result: EvalResult,
    ): Double {
        if (!result.feasible || result.objective == null) return 0.0
        val found = result.objective
        val reference = references[ReferenceStore.suiteOf(entry.ref) to entry.name]
        val referenceObjective = reference?.objective ?: return UNREFERENCED_FEASIBLE_REWARD
        // The table stores the objective in the model's orientation; convert to the minimised one the
        // eval reports, so a smaller `found` is always better and `found >= optimum`.
        val optimum = if (reference.maximize) -referenceObjective else referenceObjective
        val gap = (found - optimum).coerceAtLeast(0.0)
        val denom = maxOf(abs(optimum), abs(found), 1.0)
        return (1.0 - gap / denom).coerceIn(0.0, 1.0)
    }
}

/** The engine axis for the `bench tune` command. */
internal enum class TuneEngine { LS, BT }
