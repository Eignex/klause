package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/**
 * Compile-only audit over a MiniZinc suite — no solve. Per instance:
 *  1. compile `.mzn`→`.fzn` against klause's redefinitions (records compile failures);
 *  2. tally surviving constraint predicates as native (preserved) vs decomposed;
 *  3. optional `klause-cli` ingest smoke (short wall-clock cap) — confirms klause parses
 *     the FZN and starts searching without crashing.
 *
 * Aggregated per problem family; emits JSON + Markdown. Parallel across instances. The ingest
 * smoke needs `:klause-cli:installJvmDist`; if the binary is absent it is skipped (reported as
 * `ingest=skipped`).
 */
@Serializable
data class AuditRow(
    val name: String,
    val family: String,
    val status: String,          // OK | COMPILE_ERROR
    val nativeCount: Int,
    val decomposedCount: Int,
    val coverage: Double,
    val ingest: String,          // ok | crashed | skipped | n/a
    val decomposedPredicates: Map<String, Int> = emptyMap(),
)

@Serializable
data class AuditFamily(
    val family: String,
    val instances: Int,
    val compileErrors: Int,
    val ingestCrashes: Int,
    val avgCoverage: Double,
)

@Serializable
data class AuditResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val rows: List<AuditRow>,
    val families: List<AuditFamily>,
)

object CompileAuditMetric {
    fun run(refs: List<ProblemRef>) {
        val runner = MiniZincRunner()
        val nativeSet = FznPredicates.nativeSet
        val fznCli = fznCliBinary()
        val skipIngest = System.getProperty("klause.bench.audit.skipIngest")?.toBoolean() == true || fznCli == null
        val ingestCap = System.getProperty("klause.bench.audit.ingestTimeoutSec")?.toIntOrNull() ?: 1
        val parallelism = System.getProperty("klause.bench.audit.parallelism")?.toIntOrNull()
            ?: Runtime.getRuntime().availableProcessors()

        println()
        println("=== compile audit (compile→FZN, classify native|decomposed, ingest smoke; ${refs.size} instances) ===")
        if (skipIngest) println("(ingest smoke skipped — ${if (fznCli == null) "klause-cli not installed (run :klause-cli:installJvmDist)" else "disabled"})")

        val pool = Executors.newFixedThreadPool(parallelism)
        val rows = try {
            refs.map { ref -> pool.submit<AuditRow> { audit(ref, runner, nativeSet, fznCli.takeUnless { skipIngest }, ingestCap) } }
                .map { it.get() }
        } finally { pool.shutdown() }

        val families = rows.groupBy { it.family }.map { (fam, rs) ->
            AuditFamily(fam, rs.size, rs.count { it.status == "COMPILE_ERROR" }, rs.count { it.ingest == "crashed" },
                rs.filter { it.status == "OK" }.let { ok -> if (ok.isEmpty()) 0.0 else ok.sumOf { it.coverage } / ok.size })
        }.sortedBy { it.family }

        for (f in families) {
            println("[${f.family}] ${f.instances} inst, ${f.compileErrors} compile-err, ${f.ingestCrashes} ingest-crash, " +
                "avg cov ${"%.0f".format(f.avgCoverage * 100)}%")
        }
        val res = AuditResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows, families)
        Reports.writeJson("build/compile-audit-report.json", res)
        Reports.writeMarkdown("build/compile-audit-report.md", markdown {
            h1("MiniZinc compile audit")
            para("${rows.size} instances, ${rows.count { it.status == "COMPILE_ERROR" }} compile errors, " +
                "${rows.count { it.ingest == "crashed" }} ingest crashes.")
            h2("By family")
            table(listOf("family", "instances", "compile-err", "ingest-crash", "avg coverage"),
                families.map { listOf(it.family, it.instances, it.compileErrors, it.ingestCrashes, "${"%.0f".format(it.avgCoverage * 100)}%") })
        })
    }

    private fun audit(ref: ProblemRef, runner: MiniZincRunner, nativeSet: Set<String>, fznCli: File?, ingestCap: Int): AuditRow {
        val family = ref.name.substringBefore('/')
        val fzn = runCatching { runner.compileFzn(ref) }.getOrNull()
            ?: return AuditRow(ref.name, family, "COMPILE_ERROR", 0, 0, 0.0, "n/a")
        val counts = FznPredicates.counts(fzn)
        val nativeCount = counts.filterKeys { it in nativeSet }.values.sum()
        val decomposed = counts.filterKeys { it !in nativeSet }
        val decomposedCount = decomposed.values.sum()
        val total = nativeCount + decomposedCount
        val coverage = if (total == 0) 1.0 else nativeCount.toDouble() / total
        val ingest = if (fznCli == null) "skipped" else ingestSmoke(fznCli, fzn, ingestCap)
        return AuditRow(ref.name, family, "OK", nativeCount, decomposedCount, coverage, ingest, decomposed)
    }

    /** Run klause-cli on [fzn] with a short cap. "ok" if it ran to the cap (still
     *  searching) or exited cleanly; "crashed" if it exited non-zero before the cap. */
    private fun ingestSmoke(bin: File, fzn: File, capSec: Int): String {
        val proc = ProcessBuilder(bin.absolutePath, fzn.absolutePath).redirectErrorStream(true).start()
        proc.outputStream.close()
        val finished = proc.waitFor(capSec.toLong(), TimeUnit.SECONDS)
        if (!finished) { proc.destroyForcibly(); return "ok" }   // still searching at the cap
        return if (proc.exitValue() == 0) "ok" else "crashed"
    }

    private fun fznCliBinary(): File? =
        File(CorpusFetcher.workspaceRoot(), "klause-cli/build/install/klause-cli-jvm/bin/klause-cli")
            .takeIf { it.canExecute() }
}
