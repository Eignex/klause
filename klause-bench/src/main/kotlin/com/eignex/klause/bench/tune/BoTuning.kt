package com.eignex.klause.bench.tune

import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import kotlin.math.abs
import kotlin.math.max

/**
 * The Bayesian-optimization config search (task #24), run as **greedy residual rounds** — boosting's
 * stagewise loop with a set-cover (max) combination rule. A portfolio combines its arms by taking the
 * *best* arm's result per instance, so the palette this produces must be scored the same way: each
 * round keeps the config that best covers the instances the palette-so-far is weakest on.
 *
 * A [frontier] holds the best reward any kept config has reached on each instance (starts at 0, only
 * rises — it is exactly what the runtime portfolio would deliver). Round *k* opens a fresh, **stationary**
 * single-objective study whose objective is the marginal coverage over the *frozen* frontier —
 * `mean_i max(0, rᵢ(config) − frontierᵢ)` — so plain Vizier GP-bandit applies (no non-stationary drift).
 * Round 1 (frontier 0) scores mean reward and anchors the all-round workhorse; later rounds reward only
 * complements. The round winner joins the palette and raises the frontier; the loop stops after [rounds]
 * or when the best marginal gain falls below [GAIN_EPSILON] (diminishing coverage).
 *
 * Each config is **evaluated once**: a per-instance reward vector is cached by config label and re-scored
 * against each new frontier for free — the cache serves the residual recompute and the report, not
 * re-solve avoidance (across rounds the objective moves, so configs rarely repeat). The warm-start —
 * feeding those recomputed residuals into each round's fresh study via [TuningStudy.observe] — is a
 * modest, **ablatable** aid (`warmStart`): prior configs mostly score ~0 residual, so they prune
 * explored regions more than they point at the gap. COP-only ([InProcessEval] needs an objective).
 * Depends only on the [Tuner] seam, so the optimizer backend (Vizier / [RandomTuner]) is swappable.
 */
internal object BoTuning {
    /** Configs whose per-instance rewards tie within this are co-winners in the [ArmCalibration] report. */
    private const val TIE_EPSILON = 1e-9

    /** A round whose best marginal coverage gain is at or below this adds nothing — stop early. */
    private const val GAIN_EPSILON = 1e-6

    /** Reward for a feasible instance with no committed reference optimum — feasibility credit only,
     *  below any gap-scored reward so referenced instances drive the ranking. */
    private const val UNREFERENCED_FEASIBLE_REWARD = 0.5

    /** One palette slot: the [round] that picked it, its config [label]/[assignment], the marginal
     *  coverage [gain] it added over the prior frontier, and the [cumulativeCoverage] (mean frontier)
     *  after adding it — the concave curve that shows where returns flatten. */
    data class PaletteEntry(
        val round: Int,
        val label: String,
        val assignment: Map<String, Any>,
        val gain: Double,
        val cumulativeCoverage: Double,
    )

    /**
     * The tuning outcome. [palette] is the ordered greedy set-cover — one round winner each, most
     * complementary first. [configs]/[rewards] map every evaluated config's label to its assignment and
     * its per-instance reward vector (so a caller can materialize or re-score the winners, task #25).
     * [report] is an [ArmCalibration] set-cover over *all* evaluated configs, for the console table.
     */
    data class Result(
        val palette: List<PaletteEntry>,
        val configs: Map<String, Map<String, Any>>,
        val rewards: Map<String, DoubleArray>,
        val report: ArmCalibration.Report,
    )

    /** Tune the local-search config space with [tuner] over the COP [instances]. */
    fun tuneLs(
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        warmStart: Boolean = true,
        studyId: String = "ls-bo",
    ): Result {
        val references = ReferenceStore.load()
        return tune(
            LocalSearchConfigSpace,
            LocalSearchConfigSpace::toRecipe,
            { instance, recipe ->
                reward(
                    references,
                    instance,
                    InProcessEval.evalLs(instance, recipe, budgetMs, seed),
                )
            },
            instances, tuner, rounds, trials, batch, warmStart, studyId,
        )
    }

    /** Tune the backtrack config space with [tuner] over the COP [instances]. */
    fun tuneBt(
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        warmStart: Boolean = true,
        studyId: String = "bt-bo",
    ): Result {
        val references = ReferenceStore.load()
        return tune(
            BacktrackConfigSpace,
            BacktrackConfigSpace::toParams,
            { instance, params ->
                reward(
                    references,
                    instance,
                    InProcessEval.evalBt(instance, params, budgetMs, seed),
                )
            },
            instances, tuner, rounds, trials, batch, warmStart, studyId,
        )
    }

    /**
     * The engine-agnostic residual-round loop. [decode] turns a coerced assignment into the engine's
     * config [T] (called once per config); [reward] scores that config on one instance in `[0, 1]`
     * (higher better). Runs [rounds] greedy rounds, each a fresh study asking up to [trials] points
     * ([batch] at a time); every already-evaluated config stays a candidate (from the cache), and when
     * [warmStart] the study is also told their recomputed residuals via [TuningStudy.observe].
     */
    fun <T> tune(
        space: ConfigSpace,
        decode: (Map<String, Any>) -> T,
        reward: (ResolvedProblem, T) -> Double,
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        warmStart: Boolean,
        studyId: String,
    ): Result {
        require(instances.isNotEmpty()) { "tune needs at least one instance" }
        require(rounds >= 1 && trials >= 1 && batch >= 1) { "rounds, trials and batch must be >= 1" }
        val n = instances.size
        val frontier = DoubleArray(n) // best reward per instance across kept configs; only rises
        val rewardsByConfig = LinkedHashMap<String, DoubleArray>() // label -> per-instance reward (solved once)
        val configs = LinkedHashMap<String, Map<String, Any>>() // label -> coerced assignment
        val palette = ArrayList<PaletteEntry>()
        val kept = HashSet<String>()

        // Solve a config once and cache its per-instance reward vector; a re-suggested point is free.
        fun evaluate(assignment: Map<String, Any>): DoubleArray {
            val label = labelOf(assignment)
            rewardsByConfig[label]?.let { return it }
            val decoded = decode(assignment)
            val vector = DoubleArray(n) { i -> reward(instances[i], decoded) }
            rewardsByConfig[label] = vector
            configs[label] = assignment
            return vector
        }

        // Marginal coverage of a reward vector over the current (frozen) frontier — the round objective.
        fun gainOf(vector: DoubleArray): Double {
            var sum = 0.0
            for (i in 0 until n) sum += max(0.0, vector[i] - frontier[i])
            return sum / n
        }

        for (round in 1..rounds) {
            var best: Pair<String, DoubleArray>? = null // (label, reward vector) of the round's leader
            var bestGain = -1.0
            fun consider(label: String, vector: DoubleArray) {
                if (label in kept) return // already in the palette; its coverage is in the frontier
                val gain = gainOf(vector)
                if (gain > bestGain) {
                    bestGain = gain
                    best = label to vector
                }
            }

            tuner.openStudy(space, maximize = true, "$studyId-r$round").use { study ->
                // Every config solved in an earlier round stays a candidate (re-scored against this
                // frontier), so the best complement can win without being re-suggested or re-solved.
                // The warm-start additionally *tells* those residuals to the study (ablatable).
                for ((label, vector) in rewardsByConfig) {
                    if (warmStart) study.observe(configs.getValue(label), gainOf(vector))
                    consider(label, vector)
                }
                var evaluated = 0
                while (evaluated < trials) {
                    val ask = minOf(batch, trials - evaluated)
                    for (suggestion in study.suggest(ask)) {
                        val assignment = space.coerce(suggestion.values)
                        val vector = evaluate(assignment)
                        study.complete(suggestion, gainOf(vector))
                        consider(labelOf(assignment), vector)
                    }
                    evaluated += ask
                }
            }

            val (label, vector) = best ?: break // no candidate improved on the frontier
            if (bestGain <= GAIN_EPSILON) break // diminishing returns: nothing left to cover
            for (i in 0 until n) frontier[i] = max(frontier[i], vector[i])
            kept += label
            palette += PaletteEntry(round, label, configs.getValue(label), bestGain, frontier.average())
        }

        return Result(palette, configs, rewardsByConfig, report(rewardsByConfig, instances.size))
    }

    /** An [ArmCalibration] set-cover over every evaluated config's per-instance winner set — the
     *  console table / cross-check alongside the round-built [Result.palette]. */
    private fun report(rewardsByConfig: Map<String, DoubleArray>, instances: Int): ArmCalibration.Report {
        val labels = rewardsByConfig.keys.toList()
        val won = (0 until instances).mapNotNull { i ->
            val best = labels.maxOf { rewardsByConfig.getValue(it)[i] }
            if (best <= 0.0) {
                null
            } else {
                labels.filterTo(
                    HashSet(),
                ) { rewardsByConfig.getValue(it)[i] >= best - TIE_EPSILON }
            }
        }
        return ArmCalibration.scoreWinnerSets(labels, won, instances)
    }

    /** Stable per-config label: the coerced assignment sorted by key, so identical points collapse. */
    private fun labelOf(assignment: Map<String, Any>): String =
        assignment.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    /**
     * Reference-normalised gap-to-optimum reward in `[0, 1]` (higher is better). Infeasible → 0. A
     * feasible result with a committed reference optimum is scored `1 - gap/denom` where `gap` is how
     * far the found objective sits above the optimum (both in the minimised orientation) and `denom`
     * normalises across instances of wildly different scale; with no committed optimum it earns only
     * [UNREFERENCED_FEASIBLE_REWARD]. Comparable across a heterogeneous corpus, which is what lets the
     * coverage objective mean the same thing on every instance.
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
