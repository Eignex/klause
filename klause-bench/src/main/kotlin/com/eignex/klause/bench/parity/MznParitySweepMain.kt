package com.eignex.klause.bench.parity

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full-sweep MiniZinc parity entry point. Walks one or more [MznParityCorpus.Source]s,
 * runs [MznParity.run] against each, and writes a JSON report.
 *
 * Configuration via system properties (all optional):
 *
 *  - `klause.parity.source` — comma-separated source ids: `smoke`, `mzn-bench`,
 *    `libminizinc-tests`, `hakank`. Default: `smoke`.
 *  - `klause.parity.timeoutSec` — per-instance timeout for klause + reference. Default 30.
 *  - `klause.parity.maxInstances` — cap total instances per source. Default unbounded.
 *  - `klause.parity.report` — output path. Default `klause-bench/build/parity-report.json`.
 *  - `klause.parity.failOnNonOk` — exit 1 if any instance is not OK. Default false.
 *
 * Invoked as a Gradle task (`:klause-bench:runMznParity`) — see the build.gradle.kts
 * registration.
 */
object MznParitySweepMain {

    @Serializable
    data class Report(
        val sources: List<String>,
        val results: List<MznParity.Result>,
        val aggregate: Aggregate,
    )

    @Serializable
    data class Aggregate(
        val total: Int,
        val ok: Int,
        val compileError: Int,
        val klauseInfeasible: Int,
        val klauseTimeout: Int,
        val satDisagreement: Int,
        val optMismatch: Int,
        val other: Int,
        val avgNativeCoverage: Double,
        /** Predicates that appeared in at least one decomposed slot across the sweep. The
         *  highest-leverage additions to klause's native predicate set. */
        val decomposedTopHits: Map<String, Int>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val sourceIds = System.getProperty("klause.parity.source", "smoke").split(",").map { it.trim() }
        val timeoutSec = System.getProperty("klause.parity.timeoutSec", "30").toInt()
        val maxInstances = System.getProperty("klause.parity.maxInstances")?.toIntOrNull()
        val reportPath = File(System.getProperty("klause.parity.report",
            "klause-bench/build/parity-report.json"))
        val failOnNonOk = System.getProperty("klause.parity.failOnNonOk", "false").toBoolean()

        val sources = sourceIds.map { id ->
            when (id) {
                "smoke" -> MznParityCorpus.Source.SMOKE
                "mzn-bench" -> MznParityCorpus.Source.MZN_BENCH
                "libminizinc-tests" -> MznParityCorpus.Source.LIBMINIZINC_TESTS
                "hakank" -> MznParityCorpus.Source.HAKANK
                else -> error("Unknown parity source id '$id'; use smoke|mzn-bench|libminizinc-tests|hakank")
            }
        }

        val root = MznParityCorpus.workspaceRoot()
        val msc = MznParityCorpus.klauseMsc(root)
        val mznLib = MznParityCorpus.klauseMznLibDir(root)
        require(msc.isFile) { "klause.msc not found at $msc — run :klause-fzn-cli:installDist first." }
        require(mznLib.isDirectory) { "klause MiniZinc redefinitions dir not found at $mznLib" }
        val workDir = File(root, "klause-bench/build/parity-work").also { it.mkdirs() }

        val results = mutableListOf<MznParity.Result>()
        val decomposedHits = mutableMapOf<String, Int>()
        for (src in sources) {
            val instances = MznParityCorpus.discover(src, root)
                .let { if (maxInstances != null) it.take(maxInstances) else it }
            println("[parity] source=$src instances=${instances.size}")
            for (inst in instances) {
                println("[parity] $src/${inst.name} ...")
                val cfg = MznParity.Config(
                    mznPath = inst.mzn,
                    dznPath = inst.dzn,
                    name = "${src.name.lowercase()}-${inst.name.replace('/', '_')}",
                    klauseMsc = msc,
                    klauseMznLibDir = mznLib,
                    timeoutSec = timeoutSec,
                    workDir = workDir,
                )
                val r = runCatching { MznParity.run(cfg) }.getOrElse { ex ->
                    MznParity.Result(
                        name = cfg.name,
                        verdict = MznParity.Verdict.UNKNOWN_ERROR,
                        klauseMs = 0L,
                        referenceMs = 0L,
                        predicateUsage = emptyMap(),
                        nativeUsed = emptyList(),
                        decomposedUsed = emptyList(),
                        nativeCoverage = 0.0,
                        detail = "harness threw: ${ex.message}",
                    )
                }
                println("[parity]   verdict=${r.verdict} nativeCov=${"%.2f".format(r.nativeCoverage * 100)}% klauseMs=${r.klauseMs} refMs=${r.referenceMs}")
                for (p in r.decomposedUsed) decomposedHits.merge(p, r.predicateUsage.getValue(p)) { a, b -> a + b }
                results += r
            }
        }

        val aggregate = Aggregate(
            total = results.size,
            ok = results.count { it.verdict == MznParity.Verdict.OK },
            compileError = results.count { it.verdict == MznParity.Verdict.COMPILE_ERROR },
            klauseInfeasible = results.count { it.verdict == MznParity.Verdict.KLAUSE_INFEASIBLE },
            klauseTimeout = results.count { it.verdict == MznParity.Verdict.KLAUSE_TIMEOUT },
            satDisagreement = results.count { it.verdict == MznParity.Verdict.SAT_DISAGREEMENT },
            optMismatch = results.count { it.verdict == MznParity.Verdict.OPT_VALUE_MISMATCH },
            other = results.count {
                it.verdict !in setOf(
                    MznParity.Verdict.OK,
                    MznParity.Verdict.COMPILE_ERROR,
                    MznParity.Verdict.KLAUSE_INFEASIBLE,
                    MznParity.Verdict.KLAUSE_TIMEOUT,
                    MznParity.Verdict.SAT_DISAGREEMENT,
                    MznParity.Verdict.OPT_VALUE_MISMATCH,
                )
            },
            avgNativeCoverage = if (results.isEmpty()) 0.0
                else results.sumOf { it.nativeCoverage } / results.size,
            decomposedTopHits = decomposedHits.entries.sortedByDescending { it.value }
                .take(30).associate { it.key to it.value },
        )

        val report = Report(sources = sourceIds, results = results, aggregate = aggregate)
        reportPath.parentFile?.mkdirs()
        reportPath.writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report))
        println("[parity] wrote ${reportPath.absolutePath}")
        println("[parity] aggregate: ok=${aggregate.ok}/${aggregate.total} avgCov=${"%.2f".format(aggregate.avgNativeCoverage * 100)}%")
        println("[parity] top decomposed predicates:")
        for ((p, c) in aggregate.decomposedTopHits) println("[parity]   $p × $c")

        if (failOnNonOk && aggregate.ok < aggregate.total) {
            System.err.println("[parity] failOnNonOk=true and ${aggregate.total - aggregate.ok} instances failed parity")
            kotlin.system.exitProcess(1)
        }
    }
}
