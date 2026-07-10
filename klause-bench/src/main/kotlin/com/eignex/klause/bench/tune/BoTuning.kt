package com.eignex.klause.bench.tune

import com.eignex.klause.bench.metric.ArmCalibration
import com.eignex.klause.bench.metric.ReferenceEntry
import com.eignex.klause.bench.metric.ReferenceStore
import com.eignex.klause.bench.runner.ResolvedProblem
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * The Bayesian-optimization config search (task #24), run as **greedy residual rounds** — boosting's
 * stagewise loop with a set-cover (max) combination rule. A portfolio combines its arms by taking the
 * *best* arm's result per instance, so the palette this produces is scored the same way: each round
 * keeps the config that best covers the strata the palette-so-far is weakest on.
 *
 * **Mini-batch over a pool (#39/#35).** The [SamplingPool] is a large *universe*; a config is never run
 * against all of it. Each trial samples a handful (`sampleSize`) of problems and runs the config on just
 * those, so cost is `rounds × trials × sampleSize × budget`, independent of pool size. The batch
 * coverage-gain is a *noisy* estimate — which the GP-bandit is built to handle, provided the study is
 * told so (`noisy=true` → Vizier `observation_noise=HIGH`) and the draws are unbiased.
 *
 * **Stratum-granular frontier (#35).** The coverage `frontier` is keyed by the pool's *stratum*, not by
 * instance. A per-instance frontier dilutes on a large pool (a small batch touches too few instances, so
 * most of the frontier stays 0 and the residual degrades to plain mean-reward); a stratum frontier is a
 * handful of buckets that every mini-batch covers, so the specialist-hunting residual survives any pool
 * size. A config's per-stratum reward is the mean of its sampled-instance rewards in that stratum;
 * `gainOf` averages `max(0, stratumReward − frontier[stratum])` over the strata it has touched. It stays
 * a single scalar objective — not multi-objective. ([UniformPool] gives each instance its own stratum,
 * recovering the per-instance frontier for tests.)
 *
 * Round *k* opens a fresh, **stationary** study over the *frozen* frontier; round 1 (empty frontier)
 * scores mean reward and anchors the all-round workhorse, later rounds reward only complements. The
 * round winner (best `gainOf`) joins the palette and raises the frontier on the strata it touched. Each
 * `(config, instance)` reward is **solved once** and cached, so a re-sampled pair is free and every
 * evaluated config stays a candidate. The warm-start (telling a fresh study the cached configs'
 * recomputed residuals via [TuningStudy.observe]) is a modest, **ablatable** aid (`warmStart`). The
 * per-instance reward branches on kind — gap-to-optimum for a COP, time-to-first-feasible for a CSP.
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

    /** Default problems evaluated per trial — a handful, so a trial is cheap regardless of pool size. */
    const val DEFAULT_SAMPLE_SIZE = 5

    /** One palette slot: the [round] that picked it, its config [label]/[assignment], the marginal
     *  coverage [gain] it added over the prior frontier, and the [cumulativeCoverage] (mean stratum
     *  frontier) after adding it — the concave curve that shows where returns flatten. */
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
     * maps it to a sparse per-instance reward map keyed by [instanceKey] (only the instances the config
     * was sampled on). [report] is an [ArmCalibration] set-cover over all evaluated configs.
     */
    data class Result(
        val palette: List<PaletteEntry>,
        val configs: Map<String, Map<String, Any>>,
        val rewards: Map<String, Map<String, Double>>,
        val report: ArmCalibration.Report,
    )

    /** Tune the local-search config space with [tuner] over the [pool] (COP or CSP). */
    @Suppress("LongParameterList")
    fun tuneLs(
        pool: SamplingPool,
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
                reward(references, instance, InProcessEval.evalLs(instance, recipe, budgetMs, seed), budgetMs)
            },
            pool, tuner, rounds, trials, batch, warmStart, studyId, sampleSize, seed,
        )
    }

    /** Tune the backtrack config space with [tuner] over the [pool] (COP or CSP). */
    @Suppress("LongParameterList")
    fun tuneBt(
        pool: SamplingPool,
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
                reward(references, instance, InProcessEval.evalBt(instance, params, budgetMs, seed), budgetMs)
            },
            pool, tuner, rounds, trials, batch, warmStart, studyId, sampleSize, seed,
        )
    }

    /**
     * The MIXED campaign outcome: [configs]/[rewards] as in [Result], plus three greedy set-cover
     * projections over the one campaign's cache — [mixed] (all configs; its LS/BT split is the emergent
     * lsShare, for `-e mixed`), [ls] (engine=ls configs only, full-depth for `-e ls`), [bt] (for `-e cp`).
     * Because every evaluated config of an engine contends in its projection, a BT-dominated *mixed*
     * palette never starves the pure LS order.
     */
    data class MixedResult(
        val configs: Map<String, Map<String, Any>>,
        val rewards: Map<String, Map<String, Double>>,
        val mixed: ArmCalibration.Report,
        val ls: ArmCalibration.Report,
        val bt: ArmCalibration.Report,
    )

    /**
     * Tune the unified engine-gated space (#34): one residual-round campaign searches LS and BT configs
     * together over [pool], routing each config's evaluation to the right engine. The reward branches by
     * kind as elsewhere. An exploration floor ([floorFraction]) forces the lagging engine so both
     * per-engine projections stay deep even if one engine dominates coverage. Returns the three set-cover
     * projections of the shared cache.
     */
    @Suppress("LongParameterList")
    fun tuneMixed(
        pool: SamplingPool,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        budgetMs: Long,
        seed: Long,
        warmStart: Boolean = true,
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
        engines: Set<String> = setOf("ls", "bt"),
        floorFraction: Double = 0.3,
        studyId: String = "mixed-bo",
    ): MixedResult {
        val references = ReferenceStore.load()
        val space = if (engines == setOf("ls", "bt")) UnifiedConfigSpace else UnifiedConfigSpace.restricted(engines)
        val result = tune(
            space,
            UnifiedConfigSpace::decode,
            { instance, cfg ->
                val eval = when (cfg) {
                    is EngineConfig.Ls -> InProcessEval.evalLs(instance, cfg.recipe, budgetMs, seed)
                    is EngineConfig.Bt -> InProcessEval.evalBt(instance, cfg.params, budgetMs, seed)
                }
                reward(references, instance, eval, budgetMs)
            },
            pool, tuner, rounds, trials, batch, warmStart, studyId, sampleSize, seed,
            forced = engineFloor(floorFraction, engines),
        )
        val lsRewards = result.rewards.filterKeys { result.configs.getValue(it)["engine"] != "bt" }
        val btRewards = result.rewards.filterKeys { result.configs.getValue(it)["engine"] == "bt" }
        return MixedResult(result.configs, result.rewards, result.report, report(lsRewards), report(btRewards))
    }

    /** Exploration floor for [tuneMixed]: force the lagging engine (a fresh pinned sample) until each
     *  engine holds at least [floorFraction] of the evaluated configs, so a dominant engine can't starve
     *  the other's per-engine projection. Returns null once both engines clear the floor. With a single
     *  [engines] entry there is nothing to balance, so the floor is a no-op. */
    private fun engineFloor(
        floorFraction: Double,
        engines: Set<String>,
    ): (Random, Map<String, Map<String, Any>>) -> Map<String, Any>? {
        if (engines.size < 2) return { _, _ -> null }
        return { rng, evaluated ->
            val total = evaluated.size
            val ls = evaluated.values.count { it["engine"] != "bt" }
            val bt = total - ls
            when {
                total == 0 || ls.toDouble() / total < floorFraction -> UnifiedConfigSpace.samplePinned("ls", rng)
                bt.toDouble() / total < floorFraction -> UnifiedConfigSpace.samplePinned("bt", rng)
                else -> null
            }
        }
    }

    /**
     * The engine-agnostic residual-round loop. [decode] turns a coerced assignment into the engine's
     * config [T] (once per config); [reward] scores it on one instance in `[0, 1]` (higher better). Runs
     * [rounds] rounds, each a fresh **noisy** study asking up to [trials] points ([batch] at a time);
     * each trial evaluates its config on a fresh mini-batch of [sampleSize] problems drawn from [pool]
     * (seeded by [sampleSeed]). The coverage frontier is at [pool]'s stratum granularity. Every
     * evaluated config stays a candidate; when [warmStart] the study is told their recomputed residuals.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun <T> tune(
        space: ConfigSpace,
        decode: (Map<String, Any>) -> T,
        reward: (ResolvedProblem, T) -> Double,
        pool: SamplingPool,
        tuner: Tuner,
        rounds: Int,
        trials: Int,
        batch: Int,
        warmStart: Boolean,
        studyId: String,
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
        sampleSeed: Long = 0L,
        forced: ((Random, Map<String, Map<String, Any>>) -> Map<String, Any>?)? = null,
    ): Result {
        require(pool.isNotEmpty()) { "tune needs a non-empty pool" }
        require(rounds >= 1 && trials >= 1 && batch >= 1 && sampleSize >= 1) {
            "rounds, trials, batch and sampleSize must be >= 1"
        }
        val rng = Random(sampleSeed)
        val rewards = LinkedHashMap<String, HashMap<String, Double>>() // label -> instanceKey -> reward
        val configs = LinkedHashMap<String, Map<String, Any>>() // label -> coerced assignment
        val stratumByKey = HashMap<String, String>() // instanceKey -> stratum (recorded as instances are seen)
        val frontier = HashMap<String, Double>() // stratum -> best reward across kept configs; only rises
        val palette = ArrayList<PaletteEntry>()
        val kept = HashSet<String>()
        val crashed = HashSet<String>() // config labels whose create/eval threw — logged once, dropped

        // A config that throws — while being created ([phase] "create": coercing the raw suggestion or
        // decoding it into the engine config) or while being solved ([phase] "evaluate") — is a crasher,
        // not a merely-bad config: drop it from the cache/candidates entirely so no cached reward can make
        // it a palette arm, and log its cause once with which phase failed. Returns the reason so the
        // caller can report the trial *infeasible* to the tuner — steering the optimizer away from the
        // region without a fake reward that would distort its response surface.
        fun crash(label: String, t: Throwable, phase: String): String {
            rewards.remove(label)
            configs.remove(label)
            val reason = "${t::class.simpleName}: ${t.message?.take(140)}"
            if (crashed.add(label)) {
                println("[bo] config $label failed to $phase ($reason); dropped, continuing")
            }
            return reason
        }

        // Create a config from a raw suggestion (coerce its types, then decode into the engine config) and
        // solve it on a mini-batch, caching each (config, instance) reward so a re-sampled pair is free.
        // Decoded once; only the batch's uncached instances are actually solved. Returns the coerced
        // assignment on success (for the study's gain lookup), or null plus the crash reason if creation
        // or the solver threw (the config is dropped — see [crash]). Never throws, so one pathological
        // suggestion can neither abort the campaign nor slip a fake reward into the response surface. A
        // config that runs but scores poorly is NOT a crash: it keeps its genuine low reward.
        fun evaluateRaw(raw: Map<String, Any>, samples: List<ResolvedProblem>): Pair<Map<String, Any>?, String?> {
            val assignment = runCatching { space.coerce(raw) }
                .getOrElse { return null to crash(labelOf(raw), it, "create") }
            val label = labelOf(assignment)
            val decoded = runCatching { decode(assignment) }
                .getOrElse { return null to crash(label, it, "create") }
            val vector = rewards.getOrPut(label) {
                configs[label] = assignment
                HashMap()
            }
            for (p in samples) {
                val key = instanceKey(p)
                if (key in vector) continue
                val r = runCatching { reward(p, decoded) }.getOrElse { return null to crash(label, it, "evaluate") }
                stratumByKey.getOrPut(key) { pool.stratumOf(p) }
                vector[key] = r
            }
            return assignment to null
        }

        // A config's mean reward per stratum, over the instances it has been sampled on.
        fun stratumRewards(label: String): Map<String, Double> {
            val vector = rewards[label] ?: return emptyMap()
            val sum = HashMap<String, Double>()
            val count = HashMap<String, Int>()
            for ((key, r) in vector) {
                val s = stratumByKey.getValue(key)
                sum[s] = (sum[s] ?: 0.0) + r
                count[s] = (count[s] ?: 0) + 1
            }
            return sum.mapValues { it.value / count.getValue(it.key) }
        }

        // Marginal coverage over the frozen frontier, averaged over the strata the config has touched.
        fun gainOf(label: String): Double {
            val perStratum = stratumRewards(label)
            if (perStratum.isEmpty()) return 0.0
            return perStratum.entries.sumOf { max(0.0, it.value - (frontier[it.key] ?: 0.0)) } / perStratum.size
        }

        for (round in 1..rounds) {
            tuner.openStudy(space, maximize = true, "$studyId-r$round", noisy = true).use { study ->
                if (warmStart) {
                    for (label in rewards.keys.toList()) study.observe(configs.getValue(label), gainOf(label))
                }
                var evaluated = 0
                while (evaluated < trials) {
                    // Exploration floor: when `forced` returns a pinned config, evaluate it and tell the
                    // study via observe (not a suggestion) — so the lagging engine keeps getting sampled
                    // regardless of what the tuner favours, keeping the per-engine projections deep.
                    val forcedAssignment = forced?.invoke(rng, configs)
                    if (forcedAssignment != null) {
                        // A forced config is told to the study via observe (no server-side trial to mark
                        // infeasible); on a create/eval crash it is simply dropped and not observed.
                        val (assignment, _) = evaluateRaw(forcedAssignment, pool.sample(sampleSize, rng))
                        if (assignment != null) study.observe(assignment, gainOf(labelOf(assignment)))
                        evaluated++
                    } else {
                        val ask = minOf(batch, trials - evaluated)
                        for (suggestion in study.suggest(ask)) {
                            val (assignment, reason) = evaluateRaw(suggestion.values, pool.sample(sampleSize, rng))
                            if (assignment == null) {
                                study.markInfeasible(suggestion, reason ?: "config creation failed")
                            } else {
                                study.complete(suggestion, gainOf(labelOf(assignment)))
                            }
                        }
                        evaluated += ask
                    }
                }
            }

            // Winner = the best complement over the frozen frontier, across every evaluated config.
            val winner = rewards.keys.filter { it !in kept }.maxByOrNull { gainOf(it) } ?: break
            val bestGain = gainOf(winner)
            if (bestGain <= GAIN_EPSILON) break // diminishing returns: nothing left to cover
            // Raise the frontier on the strata the winner touched (stochastic; never re-solved over pool).
            for ((s, r) in stratumRewards(winner)) frontier[s] = max(frontier[s] ?: 0.0, r)
            kept += winner
            val coverage = if (frontier.isEmpty()) 0.0 else frontier.values.average()
            palette += PaletteEntry(round, winner, configs.getValue(winner), bestGain, coverage)
        }

        return Result(palette, configs, rewards, report(rewards))
    }

    /** An [ArmCalibration] set-cover over every evaluated config's per-instance winner set — the console
     *  table / cross-check alongside the round-built [Result.palette]. Only configs sampled on an
     *  instance contend for it; an instance no config scored positively on is dropped. */
    private fun report(rewards: Map<String, Map<String, Double>>): ArmCalibration.Report {
        val labels = rewards.keys.toList()
        val keys = rewards.values.flatMapTo(LinkedHashSet()) { it.keys }
        val won = keys.mapNotNull { key ->
            val here = labels.filter { rewards.getValue(it).containsKey(key) }
            val best = here.maxOfOrNull { rewards.getValue(it).getValue(key) } ?: return@mapNotNull null
            if (best <= 0.0) {
                null
            } else {
                here.filterTo(HashSet()) { rewards.getValue(it).getValue(key) >= best - TIE_EPSILON }
            }
        }
        return ArmCalibration.scoreWinnerSets(labels, won, keys.size)
    }

    /** Stable per-config label: the coerced assignment sorted by key, so identical points collapse. */
    private fun labelOf(assignment: Map<String, Any>): String =
        assignment.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    /**
     * Reference-normalised gap-to-optimum reward in `[0, 1]` (higher is better). CSP (no objective) is
     * time-to-first-feasible: proven UNSAT is decisive (1.0), a SAT witness scores by how fast it landed,
     * an undecided timeout earns nothing. COP: infeasible → 0; a feasible result with a committed optimum
     * scores `1 - gap/denom` (`gap` = how far the found objective sits above the optimum, both minimised;
     * `denom` normalises across scales); with no optimum it earns only [UNREFERENCED_FEASIBLE_REWARD].
     */
    private fun reward(
        references: Map<Pair<String, String>, ReferenceEntry>,
        entry: ResolvedProblem,
        result: EvalResult,
        budgetMs: Long,
    ): Double {
        if (entry.objective == null) {
            return when {
                result.proven -> 1.0

                result.feasible ->
                    (1.0 - (result.firstFeasibleMs ?: budgetMs).toDouble() / budgetMs).coerceIn(0.0, 1.0)

                else -> 0.0
            }
        }
        if (!result.feasible || result.objective == null) return 0.0
        val found = result.objective
        val reference = references[ReferenceStore.suiteOf(entry.ref) to entry.name]
        val referenceObjective = reference?.objective ?: return UNREFERENCED_FEASIBLE_REWARD
        val optimum = if (reference.maximize) -referenceObjective else referenceObjective
        val gap = (found - optimum).coerceAtLeast(0.0)
        val denom = maxOf(abs(optimum), abs(found), 1.0)
        return (1.0 - gap / denom).coerceIn(0.0, 1.0)
    }
}

/** The engine axis for the `bench tune` command. MIXED searches LS and BT jointly (#34). */
internal enum class TuneEngine { LS, BT, MIXED }
