package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.AnytimeMetric
import com.eignex.klause.bench.metric.CompletenessMetric
import com.eignex.klause.bench.metric.ParityMetric
import com.eignex.klause.bench.metric.TimeMetric
import com.eignex.klause.bench.metric.UniformnessMetric

/**
 * Single entry point for the bench. `./gradlew :klause-bench:bench --args="<target-id>"`.
 *
 * One generic runner resolves a [Target]'s suites, gates them through the cross-backend
 * [com.eignex.klause.bench.metric.Verifier], then dispatches to the target's metric. This
 * replaces the former one-Gradle-task-per-bench layout; problem selection lives in the
 * catalog, comparison selection lives in [Targets].
 *
 * `--args="list"` prints the available targets and catalog suites.
 */
object BenchCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val cmd = args.firstOrNull()
        if (cmd == null || cmd == "list" || cmd == "--list" || cmd == "help" || cmd == "--help") {
            printListing()
            return
        }
        val target = Targets.get(cmd)
        println("=== target '${target.id}' — ${target.description} ===")
        when (target.metric) {
            MetricKind.PARITY -> ParityMetric.run(BenchLoad.resolve(target.suiteIds), target.budget)
            MetricKind.ANYTIME -> AnytimeMetric.run(BenchLoad.resolve(target.suiteIds), target.budget)
            else -> {
                val corpus = BenchLoad.loadAndVerify(target.suiteIds)
                when (target.metric) {
                    MetricKind.VERIFY -> println("\nverification passed for ${corpus.verifyEntries.size} entries")
                    MetricKind.TIME -> TimeMetric.run(corpus.benchEntries)
                    MetricKind.UNIFORMNESS -> UniformnessMetric.run(corpus.benchEntries)
                    MetricKind.COMPLETENESS -> CompletenessMetric.run(corpus.benchEntries)
                    MetricKind.PARITY, MetricKind.ANYTIME -> error("unreachable")
                }
            }
        }
    }

    private fun printListing() {
        println("Targets:")
        for (t in Targets.all) println("  ${t.id.padEnd(20)} ${t.description}")
        println("\nSuites:")
        for (s in Catalog.suites) println("  ${s.id.padEnd(20)} ${s.problems.size} problems — ${s.description}")
        println("\nUsage: bench <target-id>")
    }
}
