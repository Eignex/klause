package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.metric.KlauseSearch
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.source.ProblemKind
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
 *   `bench <metric> [filters…]`   e.g. `bench solve suite=smtlib-core backend=ortools`
 *
 * Filters: `suite=a,b` (the token `core` expands to the in-process core) `kind=cop|csp`
 * `category=SAT,OPT` `tag=…` `name=<glob>[,…]` (comma = OR) `per-family=N` `max=N` `seed=N`
 * `backend=choco|ortools|yuck` (the `solve` solver; default klause) `timeout=<ms>`
 * `engine=backtrack|ls|mixed` `processors=N` `fixed=true` (klause search for `solve`)
 * `profile=cpu|wall|alloc` `profile-scope=solve|all` `profile-top=N`.
 *
 * Other commands:
 *  - `<preset-id>` — run a saved [Target] preset (see `list`); a preset is just a named
 *    `bench <metric> [filters]` that carries a tuned budget / curated suite mix.
 *  - `preview <metric> [filters…]` — print the instances a run would cover, without running.
 *  - `list` — presets + suites; `list <suite>` — problems in a suite.
 */
object BenchCli {
    /** CLI entry point dispatching bench subcommands. */
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()

            "preview" -> adHoc(args.drop(1), preview = true)

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
            target.backend,
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
        // `backend=` is the `solve` metric's solver id: a registered MiniZinc solver (choco/gecode/
        // yuck/…) run via `minizinc --solver`; unset (or `klause`) runs klause via klause-cli.
        val backend = (f["backend"] ?: f["reference"])?.lowercase()?.takeIf { it != "klause" }
        val profile = parseProfile(f)
        val search = parseKlauseSearch(f)
        println("=== run: $metricName over ${refs.size} instance(s) ===")
        MetricRunner.run(metric, refs, budget, backend, profile, search)
    }

    /** The klause-side search for a `solve` run, from `engine=` / `processors=` / `fixed=true`. Returns
     *  null when none are set (the metric default: mixed engine over the host core count). These map
     *  onto the portfolio's engine × threads axes; the competition tracks are filter combinations (see
     *  the README recipes). `engine`/`processors` mirror the CLI's `--engine` / `-p`/`--parallel`. */
    private fun parseKlauseSearch(f: Map<String, String>): KlauseSearch? {
        if (f["engine"] == null && f["processors"] == null && f["fixed"] == null) return null
        return KlauseSearch(
            engine = f["engine"]?.let(::parseEngine) ?: "portfolio",
            processors = f["processors"]?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors(),
            fixed = f["fixed"]?.toBoolean() ?: false,
        )
    }

    /** Map an `engine=` alias to the klause-cli `-e` value. */
    private fun parseEngine(name: String): String = when (name.lowercase()) {
        "cp", "backtrack", "bt" -> "cp"
        "ls", "localsearch", "local-search" -> "ls"
        "mixed", "portfolio" -> "portfolio"
        else -> error("engine must be backtrack|ls|mixed, got '$name'")
    }

    /** Build the selection from filters: suites (`core` expands to the in-process core;
     *  static-only unless named) → kind/category/tag/name filter → family-aware caps/sampling.
     *  `kind=cop|csp` is applied *before* sampling (via [ProblemKind]) so a capped selection
     *  fills its cap with the requested kind rather than under-filling. */
    private fun select(f: Map<String, String>): List<ProblemRef> {
        var refs: List<ProblemRef> = f["suite"]?.split(",")?.flatMap { expandSuite(it.trim()) }
            ?: Catalog.suites.flatMap { it.problems }
        f["kind"]?.let { kind ->
            val wantCop = parseKind(kind)
            refs = refs.filter { ProblemKind.isCop(it) == wantCop }
        }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            refs = refs.filter { it.category in cats }
        }
        f["tag"]?.split(
            ",",
        )?.map { it.trim() }?.toSet()?.let { tags -> refs = refs.filter { it.tags.any { t -> t in tags } } }
        // `name=` is a comma-separated OR of substring-or-`*`-glob patterns: keep an instance if
        // ANY pattern matches. Lets a curated selection list specific families, e.g.
        // `name=cvrp,nfc,mario`.
        f["name"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { pats ->
            refs = refs.filter { ref -> pats.any { matches(it, ref.name) } }
        }
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

    /** `kind=cop` keeps optimization problems, `kind=csp` keeps satisfaction problems. */
    private fun parseKind(kind: String): Boolean = when (kind.lowercase()) {
        "cop", "opt", "optimization" -> true
        "csp", "sat", "satisfaction" -> false
        else -> error("kind must be cop|csp, got '$kind'")
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
        // Escape each literal segment between `*`s (Regex.escape wraps in \Q…\E, so escaping the
        // whole pattern then substituting `*` doesn't work), and join with `.*`.
        val rx = pattern.split('*').joinToString(".*") { Regex.escape(it) }
        Regex("^$rx$").containsMatchIn(name)
    } else {
        name.contains(pattern)
    }

    private fun metricNames(): String = MetricKind.entries.joinToString(", ") { it.name.lowercase() }

    /** Short aliases that don't match a [MetricKind] name verbatim. */
    private val metricAliases = mapOf(
        "uniform" to MetricKind.UNIFORMNESS,
        "complete" to MetricKind.COMPLETENESS,
        "tune" to MetricKind.TUNING,
    )

    /** Resolve a metric by its enum name (case-insensitive) or a short [metricAliases] alias. */
    private fun metricOrNull(name: String): MetricKind? = name.lowercase().let { n ->
        MetricKind.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: metricAliases[n]
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
            |
            |Filters: suite=a,b (suite=core = in-process core) kind=cop|csp category=SAT,OPTIMIZATION
            |         tag=… name=<glob>[,…] (comma=OR) per-family=N max=N seed=N backend=<minizinc solver id> timeout=<ms>
            |         engine=backtrack|ls|mixed processors=N fixed=true (klause search for solve)
            |         profile=cpu|wall|alloc profile-scope=solve|all profile-top=N
            |
            |Examples:
            |  bench solve suite=mzn-bench kind=cop per-family=1               (klause, mixed ×cores)
            |  bench solve suite=mzn-bench backend=choco timeout=300000        (Choco baseline)
            |  bench solve suite=mzn-bench backend=yuck timeout=300000         (Yuck baseline)
            |  bench solve suite=mzn-bench engine=backtrack processors=8       (klause parallel, backtrack-only)
            |  bench solve suite=mzn-bench fixed=true                          (klause follows the model annotation)
            |  bench search suite=slack-alldiff timeout=30000 profile=cpu
            |
            |To compare solvers, run `solve` once per backend (each writes build/solve-<solver>.json) and diff offline.
            """.trimMargin(),
        )
    }
}
