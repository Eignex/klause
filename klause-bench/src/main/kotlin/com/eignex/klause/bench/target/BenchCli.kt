package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.tools.BanditProbe
import com.eignex.klause.bench.tools.CblsDiag
import com.eignex.klause.bench.tools.CpSeedProbe
import com.eignex.klause.bench.tools.FormatCoverage
import com.eignex.klause.bench.tools.LsConfigProbe
import com.eignex.klause.bench.tools.MeasureBacktrack
import com.eignex.klause.bench.tools.ProfileConfig
import com.eignex.klause.bench.tools.ProfileEvent
import com.eignex.klause.bench.tools.ProfileScope

/**
 * Single entry point for the bench: `./gradlew :klause-bench:bench --args="<command>"`.
 *
 * A run is fully described by a **metric** (what to measure) over a **selection** of problems
 * (which ones), with an optional **reference** solver and **budget**. That is exactly the
 * primary form:
 *
 *   `bench <metric> [filters…]`   e.g. `bench parity suite=smtlib-core reference=ortools`
 *
 * Filters: `suite=a,b` (the token `core` expands to the in-process core) `category=SAT,OPT`
 * `tag=…` `name=<glob>` `per-family=N` `max=N` `seed=N` `reference=choco|ortools|yuck`
 * `timeout=<ms>` `profile=cpu|wall|alloc` `profile-scope=solve|all` `profile-top=N`.
 *
 * Other commands:
 *  - `<preset-id>` — run a saved [Target] preset (see `list`); a preset is just a named
 *    `bench <metric> [filters]` that carries a tuned budget / curated suite mix.
 *  - `preview <metric> [filters…]` — print the instances a run would cover, without running.
 *  - `list` — presets + suites; `list <suite>` — problems in a suite.
 *  - `diag:*` / `format-coverage:*` — diagnostics and whole-library format-coverage reports
 *    (parse/solve rates over the XCSP3 / SMT-LIB libraries; distinct from the `coverage`
 *    metric, which measures native-predicate coverage of a model).
 */
object BenchCli {
    /** CLI entry point dispatching bench subcommands. */
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()

            "preview" -> adHoc(args.drop(1), preview = true)

            // `run` is kept as a back-compat alias for the primary `bench <metric>` form.
            "run" -> adHoc(args.drop(1), preview = false)

            "diag:backtrack" -> MeasureBacktrack.run()

            "diag:cbls" -> CblsDiag.main(args.drop(1).toTypedArray())

            "diag:lsconfig" -> LsConfigProbe.main(args.drop(1).toTypedArray())

            "diag:cpseed" -> CpSeedProbe.main(args.drop(1).toTypedArray())

            "diag:bandit" -> BanditProbe.main(args.drop(1).toTypedArray())

            "format-coverage:xcsp3" -> FormatCoverage.xcsp3()

            "format-coverage:smtlib" -> FormatCoverage.smtlib()

            // `bench <metric> [filters]` is the primary form; fall back to a preset id.
            else -> if (metricOrNull(cmd) != null) adHoc(args.toList(), preview = false) else runTarget(cmd)
        }
    }

    @Suppress("SpreadOperator")
    private fun runTarget(id: String) {
        val target = Targets.get(id)
        println("=== preset '${target.id}' — ${target.description} ===")
        MetricRunner.run(
            target.metric,
            Catalog.problems(*target.suiteIds.toTypedArray()),
            target.budget,
            target.reference,
        )
    }

    private fun adHoc(args: List<String>, preview: Boolean) {
        val metricName = args.firstOrNull() ?: error("usage: <metric> [filters…] (metrics: ${metricNames()})")
        val metric = parseMetric(metricName)
        val f = args.drop(1).filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val refs = select(f)
        if (refs.isEmpty()) {
            println("(no problems matched the selection)")
            return
        }

        if (preview) {
            println("=== preview: $metricName over ${refs.size} instance(s) ===")
            refs.forEach { println("  ${it.name}  [${it.format}/${it.category}]") }
            return
        }
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        val reference = f["reference"]?.let { Backend.valueOf(it.uppercase().replace("-", "")) }
        val profile = parseProfile(f)
        println("=== run: $metricName over ${refs.size} instance(s) ===")
        MetricRunner.run(metric, refs, budget, reference, profile)
    }

    /** Build the selection from filters: suites (`core` expands to the in-process core;
     *  static-only unless named) → category/tag/name filter → family-aware caps/sampling. */
    private fun select(f: Map<String, String>): List<ProblemRef> {
        var refs: List<ProblemRef> = f["suite"]?.split(",")?.flatMap { expandSuite(it.trim()) }
            ?: Catalog.suites.flatMap { it.problems }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            refs = refs.filter { it.category in cats }
        }
        f["tag"]?.split(
            ",",
        )?.map { it.trim() }?.toSet()?.let { tags -> refs = refs.filter { it.tags.any { t -> t in tags } } }
        f["name"]?.let { g -> refs = refs.filter { matches(g, it.name) } }
        val sel = CorpusSelection.Selection(
            perFamily = f["per-family"]?.toIntOrNull(),
            maxInstances = f["max"]?.toIntOrNull(),
            sampleSeed = f["seed"]?.toLongOrNull(),
        )
        val selected = CorpusSelection.applySelectionBy(refs, sel) { it.name.substringBefore('/') }
        // Sharding for parallel sweeps: -Dklause.bench.shard=i/n keeps every n-th selected
        // problem starting at i (0-based). Applied before resolution so each worker only
        // compiles its own rows — disjoint shards never race on the shared mzn-fzn cache.
        val shard = System.getProperty("klause.bench.shard") ?: return selected
        val (idx, n) = shard.split("/").map { it.trim().toInt() }
        require(n > 0 && idx in 0 until n) { "klause.bench.shard must be i/n with 0 <= i < n, got $shard" }
        return selected.filterIndexed { i, _ -> i % n == idx }
    }

    /** Expand a suite token: `core` → every in-process core suite; otherwise the named suite. */
    private fun expandSuite(token: String): List<ProblemRef> = when (token) {
        "core" -> Targets.IN_PROCESS_CORE.flatMap { Catalog.suite(it).problems }
        else -> Catalog.suite(token).problems
    }

    private fun parseProfile(f: Map<String, String>): ProfileConfig? {
        val ev = f["profile"] ?: return null
        val event = runCatching { ProfileEvent.valueOf(ev.uppercase()) }
            .getOrElse { error("profile must be one of cpu|wall|alloc, got '$ev'") }
        val scope = f["profile-scope"]?.let {
            runCatching { ProfileScope.valueOf(it.uppercase()) }
                .getOrElse { _ -> error("profile-scope must be solve|all, got '${f["profile-scope"]}'") }
        } ?: ProfileScope.SOLVE
        return ProfileConfig(event = event, scope = scope, topN = f["profile-top"]?.toIntOrNull() ?: 40)
    }

    private fun matches(pattern: String, name: String): Boolean = if ('*' in pattern) {
        Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$").containsMatchIn(name)
    } else {
        name.contains(pattern)
    }

    private fun metricNames(): String = MetricKind.entries.joinToString(", ") { it.name.lowercase() }

    private fun metricOrNull(name: String): MetricKind? = when (name.lowercase()) {
        "time" -> MetricKind.TIME
        "uniformness", "uniform" -> MetricKind.UNIFORMNESS
        "completeness", "complete" -> MetricKind.COMPLETENESS
        "verify" -> MetricKind.VERIFY
        "parity" -> MetricKind.PARITY
        "anytime" -> MetricKind.ANYTIME
        "coverage" -> MetricKind.COVERAGE
        "audit" -> MetricKind.AUDIT
        "tuning", "tune" -> MetricKind.TUNING
        "search" -> MetricKind.SEARCH
        "credit" -> MetricKind.CREDIT
        else -> null
    }

    private fun parseMetric(name: String): MetricKind =
        metricOrNull(name) ?: error("unknown metric '$name' (have ${metricNames()})")

    private fun listProblems(suite: String) {
        val s = Catalog.suite(suite)
        println("=== suite '${s.id}' — ${s.problems.size} problems ===")
        s.problems.forEach { println("  ${it.name.padEnd(28)} [${it.format}/${it.category}] expected=${it.expected}") }
    }

    private fun printListing() {
        println("Presets:")
        for (t in Targets.all) println("  ${t.id.padEnd(22)} ${t.description}")
        println("\nSuites:")
        for (s in Catalog.suites) println("  ${s.id.padEnd(22)} ${s.problems.size} problems — ${s.description}")
        for (d in Catalog.dynamicSuites) println("  ${d.id.padEnd(22)} (discovered) — ${d.description}")
        println("\nMetrics: ${metricNames()}")
        println(
            """
            |
            |Usage:
            |  bench <metric> [filters…]             run a metric over a selection (primary form)
            |  bench <preset-id>                     run a saved preset (see Presets above)
            |  bench preview <metric> [filters…]     show what a run would cover
            |  bench list [<suite>]                  list presets+suites, or problems in a suite
            |  bench diag:backtrack | diag:cbls <x>  diagnostics
            |  bench format-coverage:xcsp3|smtlib    parse/solve rates over a whole format library
            |
            |Filters: suite=a,b (suite=core = in-process core) category=SAT,OPTIMIZATION tag=… name=<glob>
            |         per-family=N max=N seed=N reference=choco|ortools|yuck timeout=<ms>
            |         profile=cpu|wall|alloc profile-scope=solve|all profile-top=N
            |
            |Examples:
            |  bench parity suite=smtlib-core reference=ortools
            |  bench coverage suite=mzn-bench
            |  bench search suite=slack-alldiff timeout=30000 profile=cpu
            """.trimMargin(),
        )
    }
}
