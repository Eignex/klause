package com.eignex.klause.bench.tune

import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The Bayesian-optimization config search (task #24), run as **greedy residual rounds** — boosting's
 * stagewise loop with a set-cover (max) combination rule. A portfolio combines its arms by taking the
 * *best* arm's result per instance, so the palette this produces is scored the same way: each round
 * keeps the config that best covers the instances the palette-so-far is weakest on.
 *
 * **Mini-batch, not full-set (#39).** The [tune] `instances` are a large *pool*; a config is never run
 * against all of it. Each trial samples a handful (`sampleSize`) of problems and runs the config on just
 * those, so cost is `rounds × trials × sampleSize × budget`, independent of pool size. The batch
 * coverage-gain `mean_{i∈batch} max(0, rᵢ − frontierᵢ)` is a *noisy* estimate of the config's true gain
 * — which the GP-bandit is built to handle, provided (a) the study is told the observations are noisy
 * (`noisy=true` → Vizier `observation_noise=HIGH`) and (b) the draws are unbiased (a consistent sampler,
 * so variation is zero-mean noise, not "this config got the easy instances").
 *
 * A `frontier` holds the best reward any kept config reached on each instance (starts 0, only rises).
 * Round *k* opens a fresh, **stationary** study over the *frozen* frontier (plain GP-bandit, no drift);
 * round 1 (frontier 0) scores mean reward and anchors the all-round workhorse, later rounds reward only
 * complements. The round winner is the best config by its coverage-gain over the frozen frontier
 * (computed over the instances it has been sampled on) and joins the palette; the frontier then rises
 * **sparsely** — only on the instances the winner actually touched (a stochastic frontier; the winner is
 * not re-solved over the whole pool). Stops after `rounds` or when the best gain ≤ [GAIN_EPSILON].
 *
 * Each `(config, instance)` pair is **solved once** and cached, so a re-sampled pair is free and every
 * evaluated config stays a round candidate (re-scored against the new frontier for free). The
 * warm-start — telling each round's fresh study the cached configs' recomputed residuals via
 * [TuningStudy.observe] — is a modest, **ablatable** aid (`warmStart`). The per-instance reward branches
 * on kind — gap-to-optimum for a COP, time-to-first-feasible for a CSP. Depends only on the [Tuner]
 * seam, so the optimizer backend (Vizier / [RandomTuner]) is swappable.
 */
internal object BoTuning {
    /** Configs whose per-instance rewards tie within this are co-winners in the [ArmCalibration] report. */
    private const val TIE_EPSILON = 1e-9

    /** A round whose best marginal coverage gain is at or below this adds nothing — stop early. */
    private const val GAIN_EPSILON = 1e-6

    /** Reward for a feasible instance with no committed reference optimum — feasibility credit only,
     *  below any gap-scored reward so referenced instances drive the ranking. */
    private const val UNREFERENCED_FEASIBLE_REWARD = 0.5

    /** Default problems evaluated per trial — a handful, so a trial is cheap regardless of pool size. */
    const val DEFAULT_SAMPLE_SIZE = 5

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
     * complementary first. [configs] maps every evaluated config's label to its assignment; [rewards]
     * maps it to its per-instance reward vector, `NaN` where the config was never sampled (mini-batch,
     * so vectors are partial). [report] is an [ArmCalibration] set-cover over all evaluated configs.
     */
    data class Result(
        val palette: List<PaletteEntry>,
        val configs: Map<String, Map<String, Any>>,
        val rewards: Map<String, DoubleArray>,
        val report: ArmCalibration.Report,
    )

    /** Tune the local-search config space with [tuner] over the [instances] pool (COP or CSP). */
    fun tuneLs(
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        warmStart: Boolean = true,
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
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
                    budgetMs,
                )
            },
            instances, tuner, rounds, trials, batch, warmStart, studyId, sampleSize, seed,
        )
    }

    /** Tune the backtrack config space with [tuner] over the [instances] pool (COP or CSP). */
    fun tuneBt(
        instances: List<ResolvedProblem>,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        warmStart: Boolean = true,
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
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
                    budgetMs,
                )
            },
            instances, tuner, rounds, trials, batch, warmStart, studyId, sampleSize, seed,
        )
    }

    /**
     * The engine-agnostic residual-round loop. [decode] turns a coerced assignment into the engine's
     * config [T] (once per config); [reward] scores it on one instance in `[0, 1]` (higher better). Runs
     * [rounds] rounds, each a fresh **noisy** study asking up to [trials] points ([batch] at a time);
     * each trial evaluates its config on a fresh mini-batch of [sampleSize] problems drawn by
     * [sampleBatch] (uniform by default, seeded by [sampleSeed]). Every already-evaluated config stays a
     * candidate (re-scored against the frozen frontier for free), and when [warmStart] the study is also
     * told their recomputed residuals via [TuningStudy.observe].
     */
    @Suppress("LongParameterList")
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
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
        sampleSeed: Long = 0L,
        sampleBatch: ((List<ResolvedProblem>, Random) -> List<ResolvedProblem>)? = null,
    ): Result {
        require(instances.isNotEmpty()) { "tune needs at least one instance" }
        require(rounds >= 1 && trials >= 1 && batch >= 1 && sampleSize >= 1) {
            "rounds, trials, batch and sampleSize must be >= 1"
        }
        val n = instances.size
        val index = IdentityHashMap<ResolvedProblem, Int>(n).apply { instances.forEachIndexed { i, p -> put(p, i) } }
        val rng = Random(sampleSeed)
        val sampler = sampleBatch ?: { pool, r -> uniformSample(pool, sampleSize, r) }

        val frontier = DoubleArray(n) // best reward per instance across kept configs; only rises
        val rewards = LinkedHashMap<String, DoubleArray>() // label -> per-instance reward, NaN = never sampled
        val configs = LinkedHashMap<String, Map<String, Any>>() // label -> coerced assignment
        val palette = ArrayList<PaletteEntry>()
        val kept = HashSet<String>()

        // Solve a config on a mini-batch, caching each (config, instance) reward so a re-sampled pair is
        // free. The config is decoded once; only the batch's uncached instances are actually solved.
        fun evaluateOn(assignment: Map<String, Any>, samples: List<ResolvedProblem>) {
            val label = labelOf(assignment)
            val vector = rewards.getOrPut(label) {
                configs[label] = assignment
                DoubleArray(n) { Double.NaN }
            }
            val decoded = decode(assignment)
            for (p in samples) {
                val i = index.getValue(p)
                if (vector[i].isNaN()) vector[i] = reward(p, decoded)
            }
        }

        // Marginal coverage over the frozen frontier, averaged over the instances the config has been
        // sampled on (comparable across configs since each sees ~sampleSize instances).
        fun gainOf(label: String): Double {
            val vector = rewards[label] ?: return 0.0
            var sum = 0.0
            var count = 0
            for (i in 0 until n) {
                if (!vector[i].isNaN()) {
                    sum += max(0.0, vector[i] - frontier[i])
                    count++
                }
            }
            return if (count == 0) 0.0 else sum / count
        }

        for (round in 1..rounds) {
            tuner.openStudy(space, maximize = true, "$studyId-r$round", noisy = true).use { study ->
                // Warm-start: tell the fresh study every earlier config's residual against this frontier
                // (recomputed for free from the cache). Ablatable — prior configs mostly score ~0.
                if (warmStart) {
                    for (label in rewards.keys.toList()) study.observe(configs.getValue(label), gainOf(label))
                }
                var evaluated = 0
                while (evaluated < trials) {
                    val ask = minOf(batch, trials - evaluated)
                    for (suggestion in study.suggest(ask)) {
                        val assignment = space.coerce(suggestion.values)
                        evaluateOn(assignment, sampler(instances, rng))
                        study.complete(suggestion, gainOf(labelOf(assignment)))
                    }
                    evaluated += ask
                }
            }

            // Winner = the best complement over the frozen frontier, across every evaluated config.
            var bestLabel: String? = null
            var bestGain = -1.0
            for (label in rewards.keys) {
                if (label in kept) continue
                val gain = gainOf(label)
                if (gain > bestGain) {
                    bestGain = gain
                    bestLabel = label
                }
            }
            val winner = bestLabel ?: break
            if (bestGain <= GAIN_EPSILON) break // diminishing returns: nothing left to cover
            // Raise the frontier sparsely — only where the winner was actually sampled (stochastic).
            val vector = rewards.getValue(winner)
            for (i in 0 until n) if (!vector[i].isNaN()) frontier[i] = max(frontier[i], vector[i])
            kept += winner
            palette += PaletteEntry(round, winner, configs.getValue(winner), bestGain, frontier.average())
        }

        return Result(palette, configs, rewards, report(rewards, n))
    }

    /** A uniform mini-batch of [size] distinct problems (or the whole [pool] if it is smaller). */
    private fun uniformSample(pool: List<ResolvedProblem>, size: Int, rng: Random): List<ResolvedProblem> =
        if (pool.size <= size) pool else pool.indices.shuffled(rng).take(size).map { pool[it] }

    /** An [ArmCalibration] set-cover over every evaluated config's per-instance winner set — the console
     *  table / cross-check alongside the round-built [Result.palette]. Only configs actually sampled on
     *  an instance contend for it (NaN = never sampled); an instance no config reached is dropped. */
    private fun report(rewards: Map<String, DoubleArray>, instances: Int): ArmCalibration.Report {
        val labels = rewards.keys.toList()
        val won = (0 until instances).mapNotNull { i ->
            val here = labels.filter { !rewards.getValue(it)[i].isNaN() }
            if (here.isEmpty()) return@mapNotNull null
            val best = here.maxOf { rewards.getValue(it)[i] }
            if (best <= 0.0) null else here.filterTo(HashSet()) { rewards.getValue(it)[i] >= best - TIE_EPSILON }
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
        budgetMs: Long,
    ): Double {
        // CSP (no objective): time-to-first-feasible — a proven UNSAT is decisive (matches the reference
        // oracle), a SAT witness scores by how fast it landed, an undecided timeout earns nothing.
        if (entry.objective == null) {
            return when {
                result.proven -> 1.0

                result.feasible ->
                    (1.0 - (result.firstFeasibleMs ?: budgetMs).toDouble() / budgetMs).coerceIn(0.0, 1.0)

                else -> 0.0
            }
        }
        // COP: reference-normalised gap-to-optimum.
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
