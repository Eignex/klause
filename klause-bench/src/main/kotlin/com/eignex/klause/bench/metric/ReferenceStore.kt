package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File

/**
 * A committed per-instance reference optimum/bound from a strong external solver (OR-Tools CP-SAT).
 * The gap-to-optimum BO reward reads this table, and it doubles as a soundness oracle (any solver
 * beating a [proven] optimum is a bug). [objective] is in the model's orientation ([maximize]).
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
 * The vendored reference table (`klause-bench/reference/references.csv`), instance-keyed. CSV (a header
 * plus one row per instance) rather than JSON: at ~20k entries it is a few times smaller and, being
 * line-oriented, gives clean per-instance VCS diffs (a changed optimum touches one line, not the whole
 * file). Merges are **virtual-best**: a proven optimum always wins, and among unproven bounds the
 * tighter objective (lower for minimize, higher for maximize) wins — so references only ever tighten
 * and unproven bounds stay honest. Regenerable + incremental via `bench reference`.
 */
internal object ReferenceStore {
    private val COLUMNS = listOf(
        "suite", "problem", "maximize", "objective", "feasible", "proven", "elapsedMs", "solver", "budgetMs",
        "format", "structure", "numGlobal", "numLinear", "boolHeavy",
    )

    /** The oracle-only prefix — a legacy row (pre-features) has exactly this many columns and decodes
     *  with blank/null features, so the schema extension stays backward-compatible. */
    private const val ORACLE_COLUMNS = 9

    private fun file() = File(CorpusFetcher.workspaceRoot(), "klause-bench/reference/references.csv")

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

    fun load(): Map<Pair<String, String>, ReferenceEntry> = readCsv(file()).associateBy { key(it) }

    /** Read any references.csv-schema file — the committed oracle table, or a per-run result table
     *  emitted by `solve` — into entries. Lets the analysis (`credit`) read results in the same schema. */
    fun readCsv(f: File): List<ReferenceEntry> {
        if (!f.isFile) return emptyList()
        return f.readLines().asSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .map { decode(parseCsvLine(it)) }
            .toList()
    }

    /** Write [entries] to [f] in the references.csv schema (header + one row per entry, sorted by
     *  (suite, problem)) — the single-sourced encoding, used for both the committed table and result
     *  tables. */
    fun writeCsv(f: File, entries: List<ReferenceEntry>) {
        f.parentFile?.mkdirs()
        val rows = entries.sortedWith(compareBy({ it.suite }, { it.problem })).map { encode(it) }
        f.writeText((listOf(COLUMNS.joinToString(",")) + rows).joinToString("\n", postfix = "\n"))
    }

    /** Merge [incoming] into the table (virtual-best) and write it back sorted by (suite, problem).
     *  Returns (added, tightened, unchanged). */
    fun mergeAndSave(incoming: List<ReferenceEntry>): Triple<Int, Int, Int> {
        val table = load().toMutableMap()
        var added = 0
        var tightened = 0
        var unchanged = 0
        for (e in incoming) {
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
        write(table)
        return Triple(added, tightened, unchanged)
    }

    /** Merge source-derived [features] (keyed by (suite, problem)) into the table, leaving the oracle
     *  fields untouched. Returns (updated, unmatched). */
    fun mergeFeatures(features: Map<Pair<String, String>, InstanceFeatures>): Pair<Int, Int> {
        val table = load().toMutableMap()
        var updated = 0
        var unmatched = 0
        for ((k, feat) in features) {
            val old = table[k]
            if (old == null) {
                unmatched++
                continue
            }
            table[k] = old.copy(
                format = feat.format,
                structure = feat.structure,
                numGlobal = feat.numGlobal,
                numLinear = feat.numLinear,
                boolHeavy = feat.boolHeavy,
            )
            updated++
        }
        write(table)
        return updated to unmatched
    }

    private fun write(table: Map<Pair<String, String>, ReferenceEntry>) = writeCsv(file(), table.values.toList())

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
        csv(e.solver),
        e.budgetMs.toString(),
        csv(e.format),
        csv(e.structure),
        e.numGlobal?.toString().orEmpty(),
        e.numLinear?.toString().orEmpty(),
        e.boolHeavy?.toString().orEmpty(),
    ).joinToString(",")

    /** Parse one CSV data row into a [ReferenceEntry] (test seam over the private decode). */
    fun parseRow(line: String): ReferenceEntry = decode(parseCsvLine(line))

    private fun decode(f: List<String>): ReferenceEntry {
        require(f.size >= ORACLE_COLUMNS) { "reference row has ${f.size} fields, expected >= $ORACLE_COLUMNS: $f" }
        return ReferenceEntry(
            suite = f[0],
            problem = f[1],
            maximize = f[2].toBoolean(),
            objective = f[3].ifEmpty { null }?.toDouble(),
            feasible = f[4].ifEmpty { null }?.toBoolean(),
            proven = f[5].toBoolean(),
            elapsedMs = f[6].toLong(),
            solver = f[7],
            budgetMs = f[8].toLong(),
            // Features are absent in a legacy (oracle-only) row — default to blank/null.
            format = f.getOrElse(9) { "" },
            structure = f.getOrElse(10) { "" },
            numGlobal = f.getOrNull(11)?.ifEmpty { null }?.toInt(),
            numLinear = f.getOrNull(12)?.ifEmpty { null }?.toInt(),
            boolHeavy = f.getOrNull(13)?.ifEmpty { null }?.toBoolean(),
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
