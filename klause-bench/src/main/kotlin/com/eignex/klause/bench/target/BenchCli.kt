package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.solver.Backend
import com.eignex.klause.bench.source.CorpusSelection
import com.eignex.klause.bench.tools.CblsDiag
import com.eignex.klause.bench.tools.MeasureBacktrack

/**
 * Single entry point for the bench: `./gradlew :klause-bench:bench --args="<command>"`.
 *
 * Commands:
 *  - `<target-id>` — run a predefined [Target] (see `list`).
 *  - `run <metric> [filters…]` — ad-hoc: run any metric over any selection, no predefined
 *    target needed. Filters: `suite=a,b` `category=SAT,OPT` `tag=…` `name=<glob>`
 *    `per-family=N` `max=N` `seed=N` `reference=choco|ortools` `timeout=<ms>`.
 *  - `preview <metric> [filters…]` — print the instances a `run` would cover, without running.
 *  - `list` — targets + suites; `list <suite>` — problems in a suite.
 *  - `diag:backtrack` / `diag:cbls <name|fzn>` — diagnostics.
 *
 * Metric selection lives in the catalog; comparison selection lives in targets/filters — the
 * two stay independent.
 */
object BenchCli {
    @JvmStatic
    fun main(args: Array<String>) {
        when (val cmd = args.firstOrNull() ?: "list") {
            "list", "--list", "help", "--help" -> if (args.size > 1) listProblems(args[1]) else printListing()
            "run" -> adHoc(args.drop(1), preview = false)
            "preview" -> adHoc(args.drop(1), preview = true)
            "diag:backtrack" -> MeasureBacktrack.run()
            "diag:cbls" -> CblsDiag.main(args.drop(1).toTypedArray())
            "coverage:xcsp3" -> com.eignex.klause.bench.tools.FormatCoverage.xcsp3()
            "coverage:smtlib" -> com.eignex.klause.bench.tools.FormatCoverage.smtlib()
            else -> runTarget(cmd)
        }
    }

    private fun runTarget(id: String) {
        val target = Targets.get(id)
        println("=== target '${target.id}' — ${target.description} ===")
        MetricRunner.run(target.metric, Catalog.problems(*target.suiteIds.toTypedArray()), target.budget, target.reference)
    }

    private fun adHoc(args: List<String>, preview: Boolean) {
        val metricName = args.firstOrNull() ?: error("usage: ${if (preview) "preview" else "run"} <metric> [filters…]")
        val metric = parseMetric(metricName)
        val f = args.drop(1).filter { "=" in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val refs = select(f)
        if (refs.isEmpty()) { println("(no problems matched the selection)"); return }

        if (preview) {
            println("=== preview: $metricName over ${refs.size} instance(s) ===")
            refs.forEach { println("  ${it.name}  [${it.format}/${it.category}]") }
            return
        }
        val budget = f["timeout"]?.toLongOrNull()?.let { Budget(it) } ?: Budget()
        val reference = f["reference"]?.let { Backend.valueOf(it.uppercase().replace("-", "")) }
        println("=== run: $metricName over ${refs.size} instance(s) ===")
        MetricRunner.run(metric, refs, budget, reference)
    }

    /** Build the selection from filters: suites (static-only unless named) → category/tag/name
     *  filter → family-aware caps/sampling. */
    private fun select(f: Map<String, String>): List<ProblemRef> {
        var refs: List<ProblemRef> = f["suite"]?.split(",")?.flatMap { Catalog.suite(it.trim()).problems }
            ?: Catalog.suites.flatMap { it.problems }
        f["category"]?.split(",")?.map { Category.valueOf(it.trim().uppercase()) }?.toSet()?.let { cats ->
            refs = refs.filter { it.category in cats }
        }
        f["tag"]?.split(",")?.map { it.trim() }?.toSet()?.let { tags -> refs = refs.filter { it.tags.any { t -> t in tags } } }
        f["name"]?.let { g -> refs = refs.filter { matches(g, it.name) } }
        val sel = CorpusSelection.Selection(
            perFamily = f["per-family"]?.toIntOrNull(),
            maxInstances = f["max"]?.toIntOrNull(),
            sampleSeed = f["seed"]?.toLongOrNull(),
        )
        return CorpusSelection.applySelectionBy(refs, sel) { it.name.substringBefore('/') }
    }

    private fun matches(pattern: String, name: String): Boolean =
        if ('*' in pattern) Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$").containsMatchIn(name)
        else name.contains(pattern)

    private fun parseMetric(name: String): MetricKind = when (name.lowercase()) {
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
        else -> error("unknown metric '$name' (have ${MetricKind.entries.map { it.name.lowercase() }})")
    }

    private fun listProblems(suite: String) {
        val s = Catalog.suite(suite)
        println("=== suite '${s.id}' — ${s.problems.size} problems ===")
        s.problems.forEach { println("  ${it.name.padEnd(28)} [${it.format}/${it.category}] expected=${it.expected}") }
    }

    private fun printListing() {
        println("Targets:")
        for (t in Targets.all) println("  ${t.id.padEnd(20)} ${t.description}")
        println("\nSuites:")
        for (s in Catalog.suites) println("  ${s.id.padEnd(20)} ${s.problems.size} problems — ${s.description}")
        for (d in Catalog.dynamicSuites) println("  ${d.id.padEnd(20)} (discovered) — ${d.description}")
        println("\nMetrics (for `run`/`preview`): ${MetricKind.entries.joinToString(", ") { it.name.lowercase() }}")
        println(
            """
            |
            |Usage:
            |  bench <target-id>                     run a predefined target
            |  bench run <metric> [filters…]         ad-hoc: any metric over any selection
            |  bench preview <metric> [filters…]     show what a run would cover
            |  bench list [<suite>]                  list targets+suites, or problems in a suite
            |  bench diag:backtrack | diag:cbls <x>  diagnostics
            |
            |Filters: suite=a,b category=SAT,OPTIMIZATION tag=… name=<glob> per-family=N max=N seed=N reference=choco|ortools timeout=<ms>
            """.trimMargin()
        )
    }
}
