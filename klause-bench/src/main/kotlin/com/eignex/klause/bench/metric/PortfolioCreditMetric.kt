package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.solver.Cancellation
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Per-worker **credit campaign** over a portfolio: for each optimization instance, race the
 * configured portfolio to the budget and attribute every strict global improvement to the
 * worker that produced it (via
 * [com.eignex.klause.portfolio.Portfolio.improvementsAttributed]). Works for any composition —
 * pure LS, pure CP (backtrack), or mixed — because attribution keys on worker labels.
 *
 * Aggregation reports, per worker config: how often it produced the **first** global incumbent,
 * held the **final best**, was the **sole** contributor, and its total strict improvements —
 * plus a greedy **marginal-contribution** ranking (each slot is awarded to the config adding
 * the most instances not covered by the slots above it, ties broken by final-bests it would
 * hold). Marginal ranking is the palette-ordering signal: raw credit overrates configs whose
 * wins are duplicated by others.
 *
 * Knobs (forwarded `-Dklause.*` system properties):
 *  - `klause.bench.credit.portfolio=<ls>:<bt>` — worker counts (default `8:0`; `0:8` = pure CP,
 *    `8:8` = mixed).
 *  - `klause.bench.credit.configs=all|<label,…>` — explicit LS config selection from the named
 *    pool, overriding the `<ls>` count (the campaign knob).
 *  - `klause.bench.credit.seed=<N>` — base portfolio RNG seed (default 1); campaigns should be
 *    run at two or more seeds before re-deriving the palette ordering.
 */
@Serializable
data class CreditRow(
    internal val name: String,
    internal val workers: Int,
    /** Label of the worker that produced the first global incumbent; null = no incumbent. */
    val first: String? = null,
    internal val firstMs: Long = -1,
    /** Label of the worker holding the final best. */
    val best: String? = null,
    /** Strict global improvements per worker label. */
    val contrib: Map<String, Int> = emptyMap(),
)

@Serializable
internal data class CreditAggregate(
    val label: String,
    val firsts: Int,
    val bests: Int,
    val soles: Int,
    val improvements: Int,
)

@Serializable
internal data class MarginalSlot(
    val rank: Int,
    val label: String,
    /** Instances this config covers that no higher-ranked config touched. */
    val addedUncovered: Int,
    /** Instances where this config holds the final best and no higher-ranked config does. */
    val addedBest: Int,
)

@Serializable
internal data class CreditResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val budgetMillis: Long,
    val portfolio: String,
    val rows: List<CreditRow>,
    val aggregates: List<CreditAggregate>,
    val marginal: List<MarginalSlot>,
)

internal object PortfolioCreditMetric {
    fun run(entries: List<ResolvedProblem>, budget: Budget = Budget()) {
        val prop = System.getProperty("klause.bench.credit.portfolio") ?: "8:0"
        val parts = prop.split(":", ",")
        val ls = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val bt = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val configs = System.getProperty("klause.bench.credit.configs")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val seed = System.getProperty("klause.bench.credit.seed")?.toLongOrNull() ?: 1L
        val opt = entries.filter { it.objective != null }
        println()
        println(
            "=== portfolio credit (attribute first/best/sole per worker; portfolio=$prop" +
                "${configs?.let { " configs=${it.joinToString(",")}" }.orEmpty()}; seed=$seed; " +
                "${budget.timeoutMillis}ms budget; ${opt.size} optimization instance(s)) ===",
        )
        if (opt.isEmpty()) {
            println("(no optimization instances in this selection)")
            return
        }
        val rows = opt.map { row(it, ls, bt, configs, seed, budget) }
        val aggregates = aggregate(rows)
        val marginal = marginalRanking(rows)
        report(rows, aggregates, marginal, budget, prop)
    }

    @Suppress("TooGenericExceptionCaught", "InjectDispatcher")
    private fun row(
        entry: ResolvedProblem,
        ls: Int,
        bt: Int,
        configs: List<String>?,
        seed: Long,
        budget: Budget,
    ): CreditRow {
        val workers = if (configs != null) {
            // The campaign measures an explicit config mix for per-worker attribution.
            PortfolioBuilder.buildExplicit(
                entry.problem,
                lsLabels = configs,
                backtrackWorkers = bt,
                kind = Kind.COP,
                seed = seed,
                objective = entry.objective,
                lsObjective = entry.lsObjective,
                definitionalSweep = entry.definitionalSweep,
            )
        } else {
            PortfolioBuilder.build(
                entry.problem,
                PortfolioScenario.parallel(
                    threads = ls + bt,
                    kind = Kind.COP,
                    engine = if (bt > 0) EngineMix.MIXED else EngineMix.LOCAL_SEARCH,
                    seed = seed,
                ),
                objective = entry.objective,
                lsObjective = entry.lsObjective,
                definitionalSweep = entry.definitionalSweep,
            )
        }
        val portfolio = Portfolio(workers)
        val deadline = System.currentTimeMillis() + budget.timeoutMillis
        val cancel = Cancellation { System.currentTimeMillis() > deadline }
        var first: String? = null
        var firstMs = -1L
        var last: String? = null
        val contrib = LinkedHashMap<String, Int>()
        try {
            portfolio.improvementsAttributed(cancel).forEach { a ->
                if (first == null) {
                    first = a.workerLabel
                    firstMs = a.elapsed.inWholeMilliseconds
                }
                last = a.workerLabel
                contrib[a.workerLabel] = (contrib[a.workerLabel] ?: 0) + 1
            }
        } catch (e: Exception) {
            System.err.println("[credit] portfolio aborted on ${entry.name}: ${e.message}")
        } finally {
            portfolio.close()
        }
        val row = CreditRow(entry.name, portfolio.workers.size, first, firstMs, last, contrib)
        println(
            "[${row.name}] " + if (first == null) {
                "no incumbent"
            } else {
                "first=$first@${firstMs}ms best=$last " +
                    "contrib=${contrib.entries.joinToString(",") { "${it.key}:${it.value}" }}"
            },
        )
        return row
    }

    private fun aggregate(rows: List<CreditRow>): List<CreditAggregate> {
        val labels = rows.flatMap { it.contrib.keys }.toSortedSet()
        return labels.map { l ->
            CreditAggregate(
                label = l,
                firsts = rows.count { it.first == l },
                bests = rows.count { it.best == l },
                soles = rows.count { it.contrib.size == 1 && it.contrib.containsKey(l) },
                improvements = rows.sumOf { it.contrib[l] ?: 0 },
            )
        }.sortedWith(compareByDescending<CreditAggregate> { it.firsts }.thenByDescending { it.bests })
    }

    /** Greedy marginal-contribution ranking: each slot goes to the config adding the most
     *  instances no higher slot covers (ties: final-bests it would newly hold). Configs adding
     *  nothing on either axis are omitted — they are redundant given the slots above. */
    private fun marginalRanking(rows: List<CreditRow>): List<MarginalSlot> {
        val labels = rows.flatMap { it.contrib.keys }.toSortedSet().toMutableList()
        val selected = ArrayList<MarginalSlot>()
        val chosen = HashSet<String>()
        while (labels.isNotEmpty()) {
            var bestLabel: String? = null
            var bestScore = Pair(-1, -1)
            for (c in labels) {
                var uncovered = 0
                var bestAdd = 0
                for (r in rows) {
                    if (!r.contrib.containsKey(c)) continue
                    if (chosen.none { r.contrib.containsKey(it) }) uncovered++
                    if (r.best == c && r.best !in chosen) bestAdd++
                }
                val score = Pair(uncovered, bestAdd)
                if (score.first > bestScore.first ||
                    (score.first == bestScore.first && score.second > bestScore.second)
                ) {
                    bestScore = score
                    bestLabel = c
                }
            }
            val label = bestLabel ?: break
            if (bestScore.first == 0 && bestScore.second == 0) break
            selected += MarginalSlot(selected.size + 1, label, bestScore.first, bestScore.second)
            chosen += label
            labels.remove(label)
        }
        return selected
    }

    private fun report(
        rows: List<CreditRow>,
        aggregates: List<CreditAggregate>,
        marginal: List<MarginalSlot>,
        budget: Budget,
        portfolio: String,
    ) {
        println()
        println("--- aggregate credit (first/best/sole/improvements) ---")
        for (a in aggregates) println("  ${a.label.padEnd(32)} ${a.firsts}/${a.bests}/${a.soles}/${a.improvements}")
        println("--- marginal ranking (greedy set cover; omitted = redundant) ---")
        for (m in marginal) {
            println(
                "  ${m.rank.toString().padStart(
                    2,
                )}. ${m.label.padEnd(32)} +uncovered=${m.addedUncovered} +best=${m.addedBest}",
            )
        }
        val res = CreditResults(
            Instant.now().toString(),
            Reports.readGitSha(),
            EnvInfo.capture(),
            budget.timeoutMillis,
            portfolio,
            rows,
            aggregates,
            marginal,
        )
        Reports.writeJson("build/portfolio-credit-report.json", res)
        Reports.writeMarkdown(
            "build/portfolio-credit-report.md",
            markdown {
                h1("Portfolio credit campaign")
                para(
                    "Portfolio $portfolio, ${budget.timeoutMillis}ms budget, ${rows.size} instances " +
                        "(${rows.count { it.first == null }} without incumbent).",
                )
                h2("Aggregate credit")
                table(
                    listOf("config", "firsts", "bests", "soles", "improvements"),
                    aggregates.map {
                        listOf(
                            it.label,
                            it.firsts,
                            it.bests,
                            it.soles,
                            it.improvements,
                        ).map(Any::toString)
                    },
                )
                h2("Marginal-contribution ranking")
                para(
                    "Greedy set cover: each slot adds the most instances uncovered by the slots above; " +
                        "omitted configs are redundant.",
                )
                table(
                    listOf("rank", "config", "+uncovered", "+best"),
                    marginal.map { listOf(it.rank, it.label, it.addedUncovered, it.addedBest).map(Any::toString) },
                )
            },
        )
    }
}
