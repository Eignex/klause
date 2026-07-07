package com.eignex.klause.bench.metric

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
    private val COLUMNS =
        listOf("suite", "problem", "maximize", "objective", "feasible", "proven", "elapsedMs", "solver", "budgetMs")

    private fun file() = File(CorpusFetcher.workspaceRoot(), "klause-bench/reference/references.csv")

    /** Table key: (suite, problem) — a bare name is not unique across corpora. */
    private fun key(e: ReferenceEntry) = e.suite to e.problem

    fun load(): Map<Pair<String, String>, ReferenceEntry> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return f.readLines().asSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .map { decode(parseCsvLine(it)) }
            .associateBy { key(it) }
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
        val f = file()
        f.parentFile?.mkdirs()
        val rows = table.values.sortedWith(compareBy({ it.suite }, { it.problem })).map { encode(it) }
        f.writeText((listOf(COLUMNS.joinToString(",")) + rows).joinToString("\n", postfix = "\n"))
        return Triple(added, tightened, unchanged)
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
        csv(e.solver),
        e.budgetMs.toString(),
    ).joinToString(",")

    private fun decode(f: List<String>): ReferenceEntry {
        require(f.size == COLUMNS.size) { "reference row has ${f.size} fields, expected ${COLUMNS.size}: $f" }
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
