package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * A committed per-instance reference optimum/bound from a strong external solver (OR-Tools CP-SAT).
 * The gap-to-optimum BO reward reads this table, and it doubles as a soundness oracle (any solver
 * beating a [proven] optimum is a bug). [objective] is in the model's orientation ([maximize]).
 */
@Serializable
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
 * The vendored reference table (`klause-bench/reference/references.json`), instance-keyed. Merges are
 * **virtual-best**: a proven optimum always wins, and among unproven bounds the tighter objective
 * (lower for minimize, higher for maximize) wins — so references only ever tighten and unproven bounds
 * stay honest. Regenerable + incremental via `bench reference`.
 */
internal object ReferenceStore {
    private fun file() = File(CorpusFetcher.workspaceRoot(), "klause-bench/reference/references.json")

    /** Table key: (suite, problem) — a bare name is not unique across corpora. */
    private fun key(e: ReferenceEntry) = e.suite to e.problem

    fun load(): Map<Pair<String, String>, ReferenceEntry> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return Reports.json.decodeFromString<List<ReferenceEntry>>(f.readText()).associateBy { key(it) }
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
        f.writeText(Reports.json.encodeToString(table.values.sortedWith(compareBy({ it.suite }, { it.problem }))))
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
}
