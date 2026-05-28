package com.eignex.klause.bench.parity

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compile-only audit across the parity corpus. For each instance:
 *  1. Run `minizinc -c --solver klause-ls.msc` to emit FlatZinc.
 *  2. Parse the FlatZinc, tallying constraint kinds. Globals we treat as native
 *     ([NATIVE_GLOBALS]) report as "preserved"; everything else is decomposed.
 *  3. Run `klause-fzn-cli` with a 1s wall-clock cap to check klause ingests the
 *     FlatZinc and starts searching without crashing.
 *
 * Output: JSON per-instance + Markdown family aggregate. No solve work; the whole
 * corpus (~17k instances) fits comfortably in single-digit minutes per family pass.
 *
 * Properties (all optional):
 *  - `klause.lscompile.source` — corpus source id. Default `mzn-bench`.
 *  - `klause.lscompile.perFamily` — samples per problem family. Default 1.
 *  - `klause.lscompile.maxInstances` — overall cap on selected instances (applied after
 *    perFamily). Default unset = no cap.
 *  - `klause.lscompile.compileTimeoutSec` — MiniZinc compile timeout. Default 30.
 *  - `klause.lscompile.ingestTimeoutSec` — klause ingest smoke timeout. Default 1.
 *  - `klause.lscompile.skipIngest` — if `true`, skip the klause-fzn-cli smoke.
 *  - `klause.lscompile.parallelism` — concurrent workers. Default = `nproc`.
 *  - `klause.lscompile.report` — output JSON path.
 */
object LsCompileAuditMain {

    /** Constraint names klause's FlatZinc compiler maps to a native factor (i.e. the
     *  global was preserved through MiniZinc instead of being decomposed). Keep in
     *  sync with `FlatZincConstraints.kt`'s dispatch table. */
    private val NATIVE_GLOBALS: Set<String> = setOf(
        "all_different_int", "fzn_all_different_int",
        "alldifferent_except_0", "alldifferent_except",
        "symmetric_all_different",
        "all_equal_int", "fzn_all_equal_int",
        "among",
        "arg_min_int", "arg_max_int",
        "array_int_minimum", "array_int_maximum",
        "bin_packing", "bin_packing_capa", "bin_packing_load", "fzn_bin_packing",
        "circuit", "fzn_circuit", "subcircuit", "fzn_subcircuit",
        "count_eq", "count_neq", "count_geq", "count_leq",
        "cumulative", "fzn_cumulative",
        "diffn", "fzn_diffn", "diffn_nonstrict", "fzn_diffn_nonstrict",
        "disjunctive", "fzn_disjunctive", "disjunctive_strict", "fzn_disjunctive_strict",
        "global_cardinality", "fzn_global_cardinality",
        "global_cardinality_closed", "fzn_global_cardinality_closed",
        "global_cardinality_low_up", "fzn_global_cardinality_low_up",
        "global_cardinality_low_up_closed", "fzn_global_cardinality_low_up_closed",
        "inverse",
        "knapsack",
        "lex_less_int", "lex_lesseq_int",
        "member_int",
        "nvalue",
        "regular", "fzn_regular", "klause_regular",
        "sequence",
        "sort", "fzn_sort",
        "table_int", "fzn_table_int", "klause_table_int",
        "value_precede_int", "value_precede_chain_int",
        // Set ops: klause decomposes var sets into per-element bool indicators at the
        // compiler level, then handles the set algebra directly in the constraint
        // emitters (emitSetUnion / emitSetIntersect / emitSetCard / emitArraySetElement /
        // emitSetIn). They are *not* MiniZinc-side decompositions.
        "set_union", "set_intersect", "set_diff", "set_symdiff",
        "set_card", "set_eq", "set_ne", "set_le", "set_lt",
        "set_subset", "set_superset", "set_in", "set_in_reif",
        "array_set_element", "array_var_set_element",
    )

    /** Whitelist of low-level FZN primitives we don't count as "decomposition" — these
     *  are the irreducible building blocks (lin/eq/le/clause). Anything outside both
     *  this set and [NATIVE_GLOBALS] is by definition decomposed from a higher-level
     *  global. */
    private val PRIMITIVES: Set<String> = setOf(
        "int_eq", "int_ne", "int_le", "int_lt",
        "int_eq_reif", "int_ne_reif", "int_le_reif", "int_lt_reif",
        "int_lin_eq", "int_lin_ne", "int_lin_le", "int_lin_lt",
        "int_lin_eq_reif", "int_lin_ne_reif", "int_lin_le_reif", "int_lin_lt_reif",
        "int_plus", "int_minus", "int_times", "int_div", "int_mod", "int_abs",
        "int_min", "int_max",
        "int_pow",
        "array_int_element", "array_var_int_element",
        "array_bool_element", "array_var_bool_element",
        "bool_eq", "bool_le", "bool_lt", "bool_xor", "bool_or", "bool_and",
        "bool_eq_reif", "bool_le_reif", "bool_lt_reif",
        "bool_clause", "bool_not", "bool2int", "bool_lin_eq", "bool_lin_le",
        "array_bool_and", "array_bool_or", "array_bool_xor",
        "set_in", "set_in_reif",
    )

    @Serializable
    data class InstanceReport(
        val name: String,
        val compileStatus: String,
        val compileMs: Long,
        val totalConstraints: Int,
        val nativeGlobalCounts: Map<String, Int>,
        val decomposedCounts: Map<String, Int>,
        val ingestStatus: String,
        val ingestMs: Long,
        val notes: String,
    )

    @Serializable
    data class FamilyAggregate(
        val family: String,
        val instances: Int,
        val compiled: Int,
        val ingested: Int,
        val withNativeGlobal: Int,
        val totalDecomposed: Int,
    )

    @Serializable
    data class Report(
        val source: String,
        val instances: List<InstanceReport>,
        val families: List<FamilyAggregate>,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val sourceId = System.getProperty("klause.lscompile.source", "mzn-bench")
        val perFamily = System.getProperty("klause.lscompile.perFamily", "1").toInt()
        val maxInstances = System.getProperty("klause.lscompile.maxInstances")?.toIntOrNull()
        val compileTimeoutSec = System.getProperty("klause.lscompile.compileTimeoutSec", "30").toInt()
        val ingestTimeoutSec = System.getProperty("klause.lscompile.ingestTimeoutSec", "1").toInt()
        val skipIngest = System.getProperty("klause.lscompile.skipIngest", "false").toBoolean()
        val parallelism = System.getProperty("klause.lscompile.parallelism")?.toIntOrNull()
            ?: Runtime.getRuntime().availableProcessors()
        val root = MznParityCorpus.workspaceRoot()
        val reportPath = System.getProperty("klause.lscompile.report")?.let { File(it) }
            ?: File(root, "klause-bench/build/lscompile-audit.json")

        val source = when (sourceId) {
            "smoke" -> MznParityCorpus.Source.SMOKE
            "mzn-bench" -> MznParityCorpus.Source.MZN_BENCH
            "libminizinc-tests" -> MznParityCorpus.Source.LIBMINIZINC_TESTS
            "hakank" -> MznParityCorpus.Source.HAKANK
            else -> error("Unknown source id '$sourceId'")
        }

        val klauseLsMsc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause-ls.msc")
        val klauseLib = MznParityCorpus.klauseMznLibDir(root)
        val klauseFznCli = File(root, "klause-fzn-cli/build/install/klause-fzn-cli/bin/klause-fzn-cli")
        require(klauseLsMsc.isFile) { "klause-ls.msc not found at $klauseLsMsc" }
        require(klauseLib.isDirectory) { "klause MiniZinc lib not found at $klauseLib" }
        if (!skipIngest) require(klauseFznCli.isFile) {
            "klause-fzn-cli not installed at $klauseFznCli; run :klause-fzn-cli:installDist first"
        }

        val instances = selectInstances(MznParityCorpus.discover(source, root), perFamily, maxInstances)
        println("[lscompile] source=$source perFamily=$perFamily maxInstances=${maxInstances ?: "∞"} " +
            "parallelism=$parallelism selected=${instances.size}")

        val tmpDir = File(System.getProperty("java.io.tmpdir"), "klause-lscompile").apply { mkdirs() }
        val pool = Executors.newFixedThreadPool(parallelism)
        val done = AtomicInteger()
        val reports: List<InstanceReport> = try {
            instances.mapIndexed { idx, inst ->
                pool.submit<InstanceReport> {
                    val fznOut = File(tmpDir, "audit_${idx}.fzn")
                    fznOut.delete()
                    val r = auditOne(inst, klauseLsMsc, klauseLib, klauseFznCli, fznOut,
                        compileTimeoutSec, ingestTimeoutSec, skipIngest)
                    fznOut.delete()
                    val n = done.incrementAndGet()
                    println("[lscompile] [${n}/${instances.size}] ${r.name}: " +
                        "compile=${r.compileStatus} ingest=${r.ingestStatus} " +
                        "globals=${r.nativeGlobalCounts.values.sum()} decomp=${r.decomposedCounts.values.sum()}")
                    r
                }
            }.map { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.MINUTES)
        }

        val families = aggregateByFamily(reports)
        val report = Report(sourceId, reports, families)
        reportPath.parentFile?.mkdirs()
        reportPath.writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report))
        println("[lscompile] wrote ${reportPath.absolutePath}")
        printSummary(reports, families)
    }

    private fun auditOne(
        inst: MznParityCorpus.Instance,
        klauseLsMsc: File, klauseLib: File, klauseFznCli: File, fznOut: File,
        compileTimeoutSec: Int, ingestTimeoutSec: Int, skipIngest: Boolean,
    ): InstanceReport {
        val compileCmd = mutableListOf(
            "minizinc", "-c", "--solver", klauseLsMsc.absolutePath,
            "-G", klauseLib.absolutePath, "-o", fznOut.absolutePath,
            inst.mzn.absolutePath,
        )
        if (inst.dzn != null) compileCmd += inst.dzn.absolutePath
        val compileStart = System.currentTimeMillis()
        val compileExec = runProcess(compileCmd, compileTimeoutSec)
        val compileMs = System.currentTimeMillis() - compileStart

        if (compileExec.timedOut) return InstanceReport(
            inst.name, "compile_timeout", compileMs, 0, emptyMap(), emptyMap(),
            "skipped", 0, compileExec.stderr.take(200),
        )
        if (compileExec.exitCode != 0 || !fznOut.isFile) return InstanceReport(
            inst.name, "compile_error", compileMs, 0, emptyMap(), emptyMap(),
            "skipped", 0, compileExec.stderr.take(200),
        )

        val (totalConstraints, nativeMap, decompMap) = parseFznConstraints(fznOut)

        val ingestStatus: String
        val ingestMs: Long
        val ingestNotes: String
        if (skipIngest) {
            ingestStatus = "skipped"; ingestMs = 0; ingestNotes = ""
        } else {
            val ingestStart = System.currentTimeMillis()
            val ingestExec = runProcess(listOf(
                klauseFznCli.absolutePath, "-e", "ls", "-t",
                ingestTimeoutSec.toString() + "000", fznOut.absolutePath,
            ), maxOf(ingestTimeoutSec + 5, 10))
            ingestMs = System.currentTimeMillis() - ingestStart
            ingestStatus = when {
                ingestExec.timedOut -> "ingest_ok_timeout"
                ingestExec.exitCode == 0 -> "ingest_ok"
                ingestExec.stderr.contains("Exception") || ingestExec.stderr.contains("Error") -> "ingest_error"
                else -> "ingest_nonzero"
            }
            ingestNotes = if (ingestStatus.startsWith("ingest_ok")) "" else ingestExec.stderr.take(200)
        }

        return InstanceReport(
            inst.name, "compile_ok", compileMs, totalConstraints, nativeMap, decompMap,
            ingestStatus, ingestMs, ingestNotes,
        )
    }

    private data class FznParse(val total: Int, val natives: Map<String, Int>, val decomp: Map<String, Int>)

    private fun parseFznConstraints(fzn: File): FznParse {
        val natives = HashMap<String, Int>()
        val decomp = HashMap<String, Int>()
        var total = 0
        fzn.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trimStart()
                if (!line.startsWith("constraint ")) continue
                total++
                val rest = line.removePrefix("constraint ").trimStart()
                val parenIdx = rest.indexOf('(')
                val name = if (parenIdx < 0) rest.trim() else rest.substring(0, parenIdx).trim()
                if (name in NATIVE_GLOBALS) {
                    natives[name] = (natives[name] ?: 0) + 1
                } else if (name !in PRIMITIVES) {
                    decomp[name] = (decomp[name] ?: 0) + 1
                }
            }
        }
        return FznParse(total, natives, decomp)
    }

    private fun aggregateByFamily(reports: List<InstanceReport>): List<FamilyAggregate> {
        val byFamily = LinkedHashMap<String, MutableList<InstanceReport>>()
        for (r in reports) {
            val fam = r.name.substringBefore('/')
            byFamily.getOrPut(fam) { mutableListOf() }.add(r)
        }
        return byFamily.map { (fam, rs) ->
            FamilyAggregate(
                family = fam,
                instances = rs.size,
                compiled = rs.count { it.compileStatus == "compile_ok" },
                ingested = rs.count { it.ingestStatus.startsWith("ingest_ok") },
                withNativeGlobal = rs.count { it.nativeGlobalCounts.isNotEmpty() },
                totalDecomposed = rs.sumOf { it.decomposedCounts.values.sum() },
            )
        }
    }

    private fun printSummary(reports: List<InstanceReport>, families: List<FamilyAggregate>) {
        val total = reports.size
        val compiled = reports.count { it.compileStatus == "compile_ok" }
        val ingested = reports.count { it.ingestStatus.startsWith("ingest_ok") }
        val withNative = reports.count { it.nativeGlobalCounts.isNotEmpty() }
        val anyDecomp = reports.count { it.decomposedCounts.isNotEmpty() }
        println("[lscompile] summary: instances=$total compile_ok=$compiled ingest_ok=$ingested " +
            "with_native_global=$withNative with_decomp=$anyDecomp")
        // Top decomposed constraint kinds (signals the next things to expose natively).
        val totalDecomp = HashMap<String, Int>()
        for (r in reports) for ((k, v) in r.decomposedCounts) totalDecomp[k] = (totalDecomp[k] ?: 0) + v
        val topDecomp = totalDecomp.entries.sortedByDescending { it.value }.take(10)
        if (topDecomp.isNotEmpty()) {
            println("[lscompile] top 10 decomposed constraint kinds (across all instances):")
            for ((k, v) in topDecomp) println("[lscompile]   $v× $k")
        }
        val troubled = families.filter { it.compiled < it.instances || it.ingested < it.compiled }
        if (troubled.isNotEmpty()) {
            println("[lscompile] families with compile/ingest issues:")
            for (f in troubled) println("[lscompile]   ${f.family}: " +
                "compiled=${f.compiled}/${f.instances} ingested=${f.ingested}/${f.compiled}")
        }
    }

    /** Apply a per-family cap, then an overall cap. Family is the slash-prefix in the
     *  instance name (e.g. `2DBinPacking/class_1_Class1_100_1` → family `2DBinPacking`).
     *  For instance names without a slash, the whole name is treated as the family. */
    internal fun selectInstances(
        all: List<MznParityCorpus.Instance>, perFamily: Int, maxInstances: Int?,
    ): List<MznParityCorpus.Instance> {
        val seenPerFam = HashMap<String, Int>()
        val out = ArrayList<MznParityCorpus.Instance>(all.size)
        for (inst in all) {
            val fam = inst.name.substringBefore('/')
            val cnt = seenPerFam[fam] ?: 0
            if (cnt >= perFamily) continue
            seenPerFam[fam] = cnt + 1
            out += inst
            if (maxInstances != null && out.size >= maxInstances) break
        }
        return out
    }

    private data class ProcessExec(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    private fun runProcess(cmd: List<String>, timeoutSec: Int): ProcessExec {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        val proc = pb.start()
        proc.outputStream.close()
        val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ProcessExec(exitCode = -1, stdout = "", stderr = "timed out", timedOut = true)
        }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        return ProcessExec(proc.exitValue(), out, err, timedOut = false)
    }
}
