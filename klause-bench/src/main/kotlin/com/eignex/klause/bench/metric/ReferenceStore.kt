package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File

/**
 * A committed per-instance reference optimum/bound from a strong external solver — cp-sat (MiniZinc,
 * XCSP3), clasp (DIMACS, OPB), z3 (SMT-LIB QF_LIA), or SCIP (MPS), per the instance's format
 * ([solver]). The gap-to-optimum BO reward reads this table, and it doubles as a soundness oracle (any
 * solver beating a [proven] optimum is a bug). [objective] is in the model's orientation ([maximize]).
 */
internal data class ReferenceEntry(
    /** The instance's corpus (its source collection id, e.g. `hakank` / `minizinc-benchmarks` /
     *  `xcsp3-cop-22to25`). Part of the table key: the same [problem] name can occur in different
     *  corpora, so entries are keyed by (suite, problem), not name alone. */
    val suite: String,
    val problem: String,
    val maximize: Boolean,
    /** COP: the best/optimal objective (null if only known infeasible). CSP: null (feasibility is in
     *  [feasible]). */
    val objective: Double?,
    /** Satisfiability status: `true` = feasible/SAT (a witness), `false` = proven infeasible/UNSAT,
     *  `null` = undecided in budget. For a COP with an [objective] this is `true`. */
    val feasible: Boolean?,
    /** COP: the [objective] is the proven optimum. CSP: the UNSAT was proven. */
    val proven: Boolean,
    /** How long the reference took: the solver's proof time when [proven], the time-to-first-feasible
     *  for an unproven witness (the CSP metric), else the full [budgetMs] it exhausted (a timeout). So
     *  a fast proof/witness stores its real time and a pure timeout stores the budget. */
    val elapsedMs: Long,
    val solver: String,
    val budgetMs: Long,
    // --- Source-text features (from `bench classify`); blank/null until an instance is classified. ---
    /** Source format: `minizinc` / `xcsp3` / `opb` / `dimacs` / …. */
    val format: String = "",
    /** Structure class: `pseudo-boolean` / `sat` / `global` / `linear` / `arithmetic` (blank = unclassified). */
    val structure: String = "",
    /** Global-constraint uses counted in the source (null = unclassified). */
    val numGlobal: Int? = null,
    /** Linear-arithmetic constraints counted in the source (null = unclassified). */
    val numLinear: Int? = null,
    /** Bool-dominated (more bool than int variable declarations); null = unclassified. */
    val boolHeavy: Boolean? = null,
)

/**
 * The vendored reference tables under `klause-bench/reference/`, one CSV **per solver** named after the
 * solver that produced it (`cp-sat.csv`, `clasp.csv`, `z3.csv`, …) — so each instance's oracle is
 * traceable to its source and independent solver runs never overwrite one another. [load] unions them
 * into one instance-keyed view. CSV (a header plus one row per instance) rather than JSON: at ~20k
 * entries it is a few times smaller and, being line-oriented, gives clean per-instance VCS diffs (a
 * changed optimum touches one line, not the whole file). Merges are **virtual-best**: a proven optimum
 * always wins, and among unproven bounds the tighter objective (lower for minimize, higher for
 * maximize) wins — so references only ever tighten and unproven bounds stay honest. Regenerable +
 * incremental via `bench reference`.
 */
internal object ReferenceStore {
    // No `solver` column: each row's solver is the file it lives in (`<solver>.csv` for a reference
    // table, `<config>.csv` for a per-run result table), so `readCsv` reads it from the file name.
    private val COLUMNS = listOf(
        "suite", "problem", "maximize", "objective", "feasible", "proven", "elapsedMs", "budgetMs",
        "format", "structure", "numGlobal", "numLinear", "boolHeavy",
    )

    /** The oracle-only prefix — a legacy row (pre-features) has exactly this many columns and decodes
     *  with blank/null features, so the schema extension stays backward-compatible. */
    private const val ORACLE_COLUMNS = 8

    private fun referenceDir() = File(CorpusFetcher.workspaceRoot(), "klause-bench/reference")

    /** The per-solver table file `reference/<solver>.csv`. Each solver writes its own table, so an
     *  instance's oracle is traceable to the solver that produced it and one solver's run never
     *  rewrites another's rows. */
    private fun file(solver: String) = File(referenceDir(), "$solver.csv")

    /** Every committed per-solver table (one `.csv` per solver in `reference/`), name-sorted so the
     *  union is deterministic. */
    private fun referenceFiles(): List<File> =
        referenceDir().listFiles { f -> f.isFile && f.extension == "csv" }?.sortedBy { it.name }.orEmpty()

    /** Table key: (suite, problem) — a bare name is not unique across corpora. */
    private fun key(e: ReferenceEntry) = e.suite to e.problem

    /** The [ref]'s corpus id — the [ReferenceEntry.suite] half of the table key: the source collection
     *  for a fetched corpus, else a path-derived label. Lets any caller look an instance up in the
     *  table by (suite, name) (the harvest keys entries this way, the BO reward reads them back). */
    fun suiteOf(ref: ProblemRef): String = when (val s = ref.source) {
        is ProblemSource.External -> s.collection.id
        is ProblemSource.ExternalIndexed -> s.collection.id
        is ProblemSource.Vendored -> s.workspaceRelPath.substringBeforeLast('/', "vendored")
        is ProblemSource.InCode -> "in-code"
    }

    /** The merged oracle across every per-solver table, keyed by (suite, problem). Solvers cover
     *  disjoint corpora, but on any overlap the virtual-best row wins ([isBetter]), so callers see one
     *  honest optimum per instance regardless of which solver produced it. */
    fun load(): Map<Pair<String, String>, ReferenceEntry> {
        val merged = HashMap<Pair<String, String>, ReferenceEntry>()
        for (f in referenceFiles()) {
            for (e in readCsv(f)) {
                val old = merged[key(e)]
                if (old == null || isBetter(e, old)) merged[key(e)] = e
            }
        }
        return merged
    }

    /** Read any reference-table-schema file — a committed oracle table, or a per-run result table
     *  emitted by `solve` — into entries. The solver is the file's base name (`<solver>.csv` /
     *  `<config>.csv`), not a column. Lets the analysis (`credit`) read results in the same schema. */
    fun readCsv(f: File): List<ReferenceEntry> {
        if (!f.isFile) return emptyList()
        val solver = f.nameWithoutExtension
        return f.readLines().asSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .map { decode(parseCsvLine(it), solver) }
            .toList()
    }

    /** Write [entries] to [f] in the reference-table schema (header + one row per entry, sorted by
     *  (suite, problem)) — the single-sourced encoding, used for both the committed table and result
     *  tables. */
    fun writeCsv(f: File, entries: List<ReferenceEntry>) {
        f.parentFile?.mkdirs()
        val rows = entries.sortedWith(compareBy({ it.suite }, { it.problem })).map { encode(it) }
        f.writeText((listOf(COLUMNS.joinToString(",")) + rows).joinToString("\n", postfix = "\n"))
    }

    /** Merge [incoming] into each producing solver's table (virtual-best) and write those tables back,
     *  sorted by (suite, problem). Each entry lands in `reference/<its solver>.csv`. Returns (added,
     *  tightened, unchanged) totalled across solvers. */
    fun mergeAndSave(incoming: List<ReferenceEntry>): Triple<Int, Int, Int> {
        var added = 0
        var tightened = 0
        var unchanged = 0
        for ((solver, group) in incoming.groupBy { it.solver }) {
            val f = file(solver)
            val table = readCsv(f).associateBy { key(it) }.toMutableMap()
            for (e in group) {
                val old = table[key(e)]
                when {
                    old == null -> {
                        table[key(e)] = e
                        added++
                    }

                    isBetter(e, old) -> {
                        table[key(e)] = e
                        tightened++
                    }

                    else -> unchanged++
                }
            }
            writeCsv(f, table.values.toList())
        }
        return Triple(added, tightened, unchanged)
    }

    /** Merge source-derived [features] (keyed by (suite, problem)) into whichever per-solver table
     *  holds each instance, leaving the oracle fields untouched. An instance lives in exactly one
     *  solver's table (disjoint corpora), so each key updates at most one file. Returns (updated,
     *  unmatched). */
    fun mergeFeatures(features: Map<Pair<String, String>, InstanceFeatures>): Pair<Int, Int> {
        val matched = HashSet<Pair<String, String>>()
        for (f in referenceFiles()) {
            val table = readCsv(f).associateBy { key(it) }.toMutableMap()
            var changed = false
            for ((k, feat) in features) {
                val old = table[k] ?: continue
                table[k] = old.copy(
                    format = feat.format,
                    structure = feat.structure,
                    numGlobal = feat.numGlobal,
                    numLinear = feat.numLinear,
                    boolHeavy = feat.boolHeavy,
                )
                matched += k
                changed = true
            }
            if (changed) writeCsv(f, table.values.toList())
        }
        return matched.size to (features.size - matched.size)
    }

    /** Whether [a] is a strictly better reference than [b]: proven beats unproven; among feasible
     *  bounds the tighter objective wins; any feasible beats none. */
    private fun isBetter(a: ReferenceEntry, b: ReferenceEntry): Boolean {
        if (a.proven != b.proven) return a.proven
        val ao = a.objective ?: return false
        val bo = b.objective ?: return true
        return if (a.maximize) ao > bo else ao < bo
    }

    private fun encode(e: ReferenceEntry): String = listOf(
        csv(e.suite),
        csv(e.problem),
        e.maximize.toString(),
        e.objective?.toString().orEmpty(),
        e.feasible?.toString().orEmpty(),
        e.proven.toString(),
        e.elapsedMs.toString(),
        e.budgetMs.toString(),
        csv(e.format),
        csv(e.structure),
        e.numGlobal?.toString().orEmpty(),
        e.numLinear?.toString().orEmpty(),
        e.boolHeavy?.toString().orEmpty(),
    ).joinToString(",")

    /** Parse one CSV data row into a [ReferenceEntry] with the given [solver] (test seam over the
     *  private decode; readers pass the source file's base name). */
    fun parseRow(line: String, solver: String = ""): ReferenceEntry = decode(parseCsvLine(line), solver)

    private fun decode(f: List<String>, solver: String): ReferenceEntry {
        require(f.size >= ORACLE_COLUMNS) { "reference row has ${f.size} fields, expected >= $ORACLE_COLUMNS: $f" }
        return ReferenceEntry(
            suite = f[0],
            problem = f[1],
            maximize = f[2].toBoolean(),
            objective = f[3].ifEmpty { null }?.toDouble(),
            feasible = f[4].ifEmpty { null }?.toBoolean(),
            proven = f[5].toBoolean(),
            elapsedMs = f[6].toLong(),
            budgetMs = f[7].toLong(),
            solver = solver,
            // Features are absent in a legacy (oracle-only) row — default to blank/null.
            format = f.getOrElse(8) { "" },
            structure = f.getOrElse(9) { "" },
            numGlobal = f.getOrNull(10)?.ifEmpty { null }?.toInt(),
            numLinear = f.getOrNull(11)?.ifEmpty { null }?.toInt(),
            boolHeavy = f.getOrNull(12)?.ifEmpty { null }?.toBoolean(),
        )
    }

    /** RFC 4180: quote a field that holds a comma, quote, CR or LF; double any interior quote. */
    private fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s

    /** Split one CSV record into fields, honouring quoted fields and doubled interior quotes. */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }

                c == '"' -> inQuotes = !inQuotes

                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.clear()
                }

                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
