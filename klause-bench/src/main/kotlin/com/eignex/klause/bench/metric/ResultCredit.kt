package com.eignex.klause.bench.metric

import java.io.File

/**
 * Credit between N per-run result tables (reference-table-schema CSVs emitted by `solve`), computed by
 * comparison rather than by parsing output dirs. For each instance shared across the files it decides a
 * **winner set** — the run(s) with the best result — then feeds those to [ArmCalibration.scoreWinnerSets]
 * for win-share + a greedy diverse set-cover, exactly as the live best-holder calibration does. Credit
 * is **relative** (who beats whom), so no oracle is needed; a committed `reference/<solver>.csv` can be
 * passed as one more file to include cp-sat as a baseline column. `by` slices the credit within each
 * value of a feature column (`structure` / `format`), so "A wins globals, B wins linear" falls out.
 *
 * This is config-level credit (one file per config/palette). Arm-level credit is the same call over
 * per-arm files — emit each arm's single-solver run as its own result CSV.
 */
internal object ResultCredit {
    private const val TIE_EPS = 1e-9

    /** Render the credit report over [files] (labelled by basename); when [by] names a feature column
     *  ("structure" / "format"), also break the credit down within each bucket. */
    fun credit(files: List<File>, by: String? = null): String {
        val labels = files.map { it.nameWithoutExtension }
        val joined = join(files, labels)
        return buildString {
            append(section("all instances", labels, joined.values))
            if (by != null) {
                val buckets = joined.values.groupBy { rows -> bucket(rows, by) }.toSortedMap()
                appendLine()
                appendLine("=== by $by ===")
                for ((value, rows) in buckets) {
                    appendLine()
                    append(section("$by=$value", labels, rows))
                }
            }
        }
    }

    /** The overall credit report over [files] (labelled by basename) — the structured form [credit]
     *  renders; exposed for testing the winner-set → set-cover pipeline directly. */
    fun report(files: List<File>): ArmCalibration.Report {
        val labels = files.map { it.nameWithoutExtension }
        return reportFor(labels, join(files, labels).values)
    }

    /** (suite, problem) -> label -> that run's row. Only instances present in >= 1 file appear. */
    private fun join(files: List<File>, labels: List<String>): Map<Pair<String, String>, Map<String, ReferenceEntry>> {
        require(files.size >= 2) { "credit needs at least two result CSVs" }
        val out = LinkedHashMap<Pair<String, String>, MutableMap<String, ReferenceEntry>>()
        for ((label, f) in labels.zip(files)) {
            for (e in ReferenceStore.readCsv(f)) out.getOrPut(e.suite to e.problem) { LinkedHashMap() }[label] = e
        }
        return out
    }

    private fun reportFor(
        labels: List<String>,
        instances: Collection<Map<String, ReferenceEntry>>,
    ): ArmCalibration.Report {
        val won = instances.mapNotNull { winners(it).ifEmpty { null } }
        return ArmCalibration.scoreWinnerSets(labels, won, instances = instances.size)
    }

    private fun section(
        title: String,
        labels: List<String>,
        instances: Collection<Map<String, ReferenceEntry>>,
    ): String = "### $title\n" + ArmCalibration.render(reportFor(labels, instances))

    /** The run(s) that win an instance. COP (any run reports an objective): best objective in the
     *  model's direction, a proven optimum breaking ties over an equal unproven bound. CSP: the
     *  fastest run that decided it (SAT witness or proved UNSAT). Unsolved runs never win; an instance
     *  no run solved yields an empty set (it discriminates nothing and is dropped). */
    private fun winners(byLabel: Map<String, ReferenceEntry>): Set<String> {
        val cop = byLabel.values.any { it.objective != null }
        if (cop) {
            val maximize = byLabel.values.first().maximize
            // Oriented objective (higher = better) for the runs that found one; unsolved runs drop out.
            val oriented = byLabel.mapNotNull { (label, e) ->
                val obj = e.objective
                if (e.feasible == true && obj != null) label to (if (maximize) obj else -obj) else null
            }.toMap()
            if (oriented.isEmpty()) return emptySet()
            val best = oriented.values.max()
            val atBest = oriented.filterValues { it >= best - TIE_EPS }.keys
            // A proven optimum is strictly better than an equal-valued unproven bound.
            val proven = atBest.filter { byLabel.getValue(it).proven }.toSet()
            return proven.ifEmpty { atBest }
        }
        val decided = byLabel.filterValues { it.feasible != null }
        if (decided.isEmpty()) return emptySet()
        val fastest = decided.values.minOf { it.elapsedMs }
        return decided.filterValues { it.elapsedMs == fastest }.keys
    }

    /** The [by] feature value shared by an instance's rows (first non-blank), or "?" when unclassified. */
    private fun bucket(byLabel: Map<String, ReferenceEntry>, by: String): String {
        val values = byLabel.values.map {
            when (by) {
                "structure" -> it.structure
                "format" -> it.format
                else -> error("unknown --by column '$by' (have: structure, format)")
            }
        }
        return values.firstOrNull { it.isNotBlank() } ?: "?"
    }
}
