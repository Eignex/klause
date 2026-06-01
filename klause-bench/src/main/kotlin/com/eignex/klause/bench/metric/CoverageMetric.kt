package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.report.markdown
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Native-predicate coverage: compile each MiniZinc instance to FlatZinc against klause's
 * redefinitions, then measure what fraction of the surviving constraint predicates klause
 * handles **natively** (vs decomposed by MiniZinc's standard library). The headline number to
 * push toward 100%. Ported from the legacy `MznParity` coverage computation.
 *
 * "Native" = every predicate declared in klause's `redefinitions.mzn` plus every constraint
 * name the `klause-fzn-cli` FlatZinc parser dispatches on (read from `FlatZincConstraints.kt`).
 */
@Serializable
data class CoverageRow(
    val name: String,
    val nativeCount: Int,
    val decomposedCount: Int,
    val coverage: Double,
    /** Decomposed predicate names with counts, for spotting what to make native next. */
    val decomposedPredicates: Map<String, Int>,
)

@Serializable
data class CoverageResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val rows: List<CoverageRow>,
    val overallCoverage: Double,
)

object CoverageMetric {
    fun run(refs: List<ProblemRef>) {
        val runner = MiniZincRunner()
        val nativeSet = loadNativePredicateSet()
        println()
        println("=== native-predicate coverage (klause native vs MiniZinc-decomposed; push toward 100%) ===")
        val rows = refs.mapNotNull { ref -> row(ref, runner, nativeSet) }
        for (r in rows) {
            val worst = r.decomposedPredicates.entries.sortedByDescending { it.value }.take(3)
                .joinToString(", ") { "${it.key}×${it.value}" }
            println("[${r.name}] ${"%.0f".format(r.coverage * 100)}% native (${r.nativeCount}/${r.nativeCount + r.decomposedCount})" +
                if (worst.isNotEmpty()) " — decomposed: $worst" else "")
        }
        val overall = rows.sumOf { it.nativeCount }.toDouble() /
            rows.sumOf { it.nativeCount + it.decomposedCount }.coerceAtLeast(1)
        println("\noverall native coverage: ${"%.1f".format(overall * 100)}% over ${rows.size} instances")

        Reports.writeJson("build/coverage-report.json",
            CoverageResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), rows, overall))
        Reports.writeMarkdown("build/coverage-report.md", markdown {
            h1("Native-predicate coverage")
            para("Overall: **${"%.1f".format(overall * 100)}%** native over ${rows.size} instances.")
            table(listOf("instance", "coverage", "native", "decomposed", "top decomposed"),
                rows.sortedBy { it.coverage }.map { r ->
                    listOf(r.name, "${"%.0f".format(r.coverage * 100)}%", r.nativeCount, r.decomposedCount,
                        r.decomposedPredicates.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.key}×${it.value}" })
                })
        })
    }

    private fun row(ref: ProblemRef, runner: MiniZincRunner, nativeSet: Set<String>): CoverageRow? {
        val fzn = runCatching { runner.compileFzn(ref) }.getOrNull() ?: return null
        val counts = parseFznPredicates(fzn)
        val native = counts.filterKeys { it in nativeSet }
        val decomposed = counts.filterKeys { it !in nativeSet }
        val nativeCount = native.values.sum()
        val decomposedCount = decomposed.values.sum()
        val total = nativeCount + decomposedCount
        return CoverageRow(ref.name, nativeCount, decomposedCount,
            if (total == 0) 1.0 else nativeCount.toDouble() / total, decomposed)
    }

    private val constraintHead = Regex("""^\s*constraint\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

    private fun parseFznPredicates(fznFile: File): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        fznFile.useLines { lines ->
            for (line in lines) constraintHead.find(line)?.let { counts.merge(it.groupValues[1], 1) { a, _ -> a + 1 } }
        }
        return counts
    }

    /** Predicates klause handles natively: redefinitions.mzn declarations + the names the
     *  klause-fzn-cli parser dispatches on (read from FlatZincConstraints.kt source). */
    private fun loadNativePredicateSet(): Set<String> {
        val root = CorpusFetcher.workspaceRoot()
        val redef = File(root, "klause-mzn-lib/share/minizinc/klause/redefinitions.mzn")
        val predicateDecl = Regex("""^\s*predicate\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val fromRedef = buildSet {
            if (redef.isFile) redef.useLines { ls -> ls.forEach { l -> predicateDecl.find(l)?.let { add(it.groupValues[1]) } } }
        }
        val constraintsKt = File(root, "klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincConstraints.kt")
        val nameLit = Regex("""\"([A-Za-z_][A-Za-z0-9_]*)\"""")
        val fromParser = buildSet {
            if (constraintsKt.isFile) constraintsKt.useLines { ls ->
                for (l in ls) if ("->" in l) for (m in nameLit.findAll(l)) if (m.groupValues[1].length > 2) add(m.groupValues[1])
            }
        }
        return fromRedef + fromParser
    }
}
