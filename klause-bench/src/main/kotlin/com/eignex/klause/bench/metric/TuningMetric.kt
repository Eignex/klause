package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale

/**
 * Solver-config tuning for a **mixed (satisfaction + optimization) workload**, run over catalog
 * suites (via the corpus-selection machinery). Every [TuneConfig] runs on every instance over
 * several seeds; the headline is each config's OVERALL average rank (per-instance dense rank,
 * averaged), with per-goal (sat / opt) ranks alongside — used to pick good klause CBLS params
 * for a Challenge-like mix.
 *
 * Scoring per instance: satisfaction → solve-rate (then time-to-feasible); optimization →
 * mean true objective over feasible seeds (configs that never reach feasible rank last).
 */
@Serializable
data class TuneCell(
    internal val config: String,
    internal val solveRate: Double,
    internal val meanObjective: Double?,
    internal val meanMs: Double,
)

@Serializable
internal data class TuneInstance(val name: String, val goal: String, val cells: List<TuneCell>)

@Serializable
internal data class TuneConfigRank(
    val config: String,
    val overallRank: Double,
    val satRank: Double?,
    val optRank: Double?,
)

@Serializable
internal data class TuningResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val seeds: Int,
    val budgetMillis: Long,
    val ranking: List<TuneConfigRank>,
    val instances: List<TuneInstance>,
)

private data class RunResult(val feasible: Boolean, val objective: Double?, val ms: Long)

private interface TuneConfig {
    val id: String
    fun satisfy(p: Problem, seed: Long, budget: Budget): RunResult
    fun optimize(p: Problem, obj: Objective, seed: Long, budget: Budget): RunResult
}

private class LsConfig(override val id: String, val shaping: CostShaping) : TuneConfig {
    private fun params(seed: Long, budget: Budget): LocalSearchParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        return LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = seed, costShaping = shaping)
            .withCancellation(Cancellation { System.currentTimeMillis() > deadline }) as LocalSearchParams
    }
    override fun satisfy(p: Problem, seed: Long, budget: Budget): RunResult {
        val t0 = System.currentTimeMillis()
        val s = LocalSearchSolver(p).sample(params(seed, budget))
        return RunResult(s.assignment != null, null, System.currentTimeMillis() - t0)
    }
    override fun optimize(p: Problem, obj: Objective, seed: Long, budget: Budget): RunResult {
        val t0 = System.currentTimeMillis()
        val r = LocalSearchSolver(p).minimize(obj, params(seed, budget))
        return RunResult(r.assignment != null, r.objectiveValue, System.currentTimeMillis() - t0)
    }
}

private class BtConfig(override val id: String) : TuneConfig {
    private fun params(seed: Long, budget: Budget): BacktrackParams {
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        return BacktrackParams(randomSeed = seed, cancellation = Cancellation { System.currentTimeMillis() > deadline })
    }
    override fun satisfy(p: Problem, seed: Long, budget: Budget): RunResult {
        val t0 = System.currentTimeMillis()
        val s = BacktrackSolver(p).sample(params(seed, budget))
        return RunResult(s.assignment != null, null, System.currentTimeMillis() - t0)
    }
    override fun optimize(p: Problem, obj: Objective, seed: Long, budget: Budget): RunResult {
        val t0 = System.currentTimeMillis()
        val r = BacktrackSolver(p).minimize(obj, params(seed, budget))
        return RunResult(r.assignment != null, r.objectiveValue, System.currentTimeMillis() - t0)
    }
}

internal object TuningMetric {
    private val CONFIGS: List<TuneConfig> = listOf(
        LsConfig("ls-feasibility-first", CostShaping.FeasibilityFirst),
        LsConfig("ls-linear-1.0", CostShaping.linear(1.0)),
        LsConfig("ls-saturating-1.0-3", CostShaping.saturating(1.0, 3.0)),
        BtConfig("backtrack"),
    )

    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget(timeoutMillis = 2_000)) {
        val seeds = System.getProperty("klause.bench.tune.seeds")?.toIntOrNull() ?: 3
        println()
        println(
            "=== tuning sweep (${CONFIGS.size} configs × $seeds seeds; ${budget.timeoutMillis}ms; " +
                "overall avg dense rank) ===",
        )

        val instances = entries.map { evaluate(it, seeds, budget) }
        // Per-instance dense rank of configs (lower rank = better), then average per config.
        val rankByConfig = HashMap<String, MutableList<Double>>()
        val rankByConfigSat = HashMap<String, MutableList<Double>>()
        val rankByConfigOpt = HashMap<String, MutableList<Double>>()
        for (inst in instances) {
            val ranks = denseRankCells(inst)
            for ((cfg, rank) in ranks) {
                rankByConfig.getOrPut(cfg) { mutableListOf() }.add(rank)
                (if (inst.goal == "optimize") rankByConfigOpt else rankByConfigSat).getOrPut(
                    cfg,
                ) { mutableListOf() }.add(rank)
            }
        }
        val ranking = CONFIGS.map { c ->
            TuneConfigRank(
                c.id,
                rankByConfig[c.id]?.average() ?: Double.NaN,
                rankByConfigSat[c.id]?.takeIf { it.isNotEmpty() }?.average(),
                rankByConfigOpt[c.id]?.takeIf { it.isNotEmpty() }?.average(),
            )
        }.sortedBy { it.overallRank }

        for (r in ranking) {
            println(
                "  ${r.config.padEnd(24)} overall=${"%.2f".format(Locale.ROOT, r.overallRank)} " +
                    "sat=${r.satRank?.let {
                        "%.2f".format(
                            Locale.ROOT,
                            it,
                        )
                    } ?: "—"} opt=${r.optRank?.let { "%.2f".format(Locale.ROOT, it) } ?: "—"}",
            )
        }
        val best = ranking.firstOrNull()?.config
        println("\nbest overall config: $best")

        Reports.writeJson(
            "build/tuning-report.json",
            TuningResults(
                Instant.now().toString(),
                Reports.readGitSha(),
                EnvInfo.capture(),
                seeds,
                budget.timeoutMillis,
                ranking,
                instances,
            ),
        )
        Reports.writeMarkdown(
            "build/tuning-report.md",
            markdown {
                h1("Solver-config tuning (mixed workload)")
                para(
                    "${CONFIGS.size} configs × $seeds seeds; ${budget.timeoutMillis}ms budget. " +
                        "Lower rank is better. Best overall: **$best**.",
                )
                table(
                    listOf("config", "overall rank", "sat rank", "opt rank"),
                    ranking.map {
                        listOf(
                            it.config,
                            "%.2f".format(Locale.ROOT, it.overallRank),
                            it.satRank?.let { r -> "%.2f".format(Locale.ROOT, r) } ?: "—",
                            it.optRank?.let { r -> "%.2f".format(Locale.ROOT, r) } ?: "—",
                        )
                    },
                )
            },
        )
    }

    private fun evaluate(entry: ResolvedProblem, seeds: Int, budget: Budget): TuneInstance {
        val obj = entry.objective
        val goal = if (obj == null) "satisfy" else "optimize"
        val cells = CONFIGS.map { cfg ->
            val runs = (0 until seeds).map { s ->
                if (obj == null) {
                    cfg.satisfy(entry.problem, s.toLong(), budget)
                } else {
                    cfg.optimize(entry.problem, obj, s.toLong(), budget)
                }
            }
            val feasibleRuns = runs.filter { it.feasible }
            TuneCell(
                config = cfg.id,
                solveRate = feasibleRuns.size.toDouble() / seeds,
                meanObjective = feasibleRuns.mapNotNull { it.objective }.takeIf { it.isNotEmpty() }?.average(),
                meanMs = runs.map { it.ms }.average(),
            )
        }
        return TuneInstance(entry.name, goal, cells)
    }

    /** Dense-rank the configs for one instance by goal-appropriate score (rank 1 = best). */
    private fun denseRankCells(inst: TuneInstance): Map<String, Double> {
        // Comparable score where SMALLER is better.
        fun score(c: TuneCell): Double = if (inst.goal == "optimize") {
            c.meanObjective ?: Double.POSITIVE_INFINITY // no feasible -> worst
        } else {
            // satisfaction: maximize solve-rate (so negate), tiebreak by time.
            -c.solveRate * 1e9 + c.meanMs
        }
        val sorted = inst.cells.sortedBy { score(it) }
        val out = HashMap<String, Double>()
        var rank = 0
        var prev: Double? = null
        for (c in sorted) {
            val s = score(c)
            if (prev == null || s != prev) rank++
            out[c.config] = rank.toDouble()
            prev = s
        }
        return out
    }
}
