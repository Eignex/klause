package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef

/**
 * Classifies CSP/COP from the format or source objective directive before sampling, without compilation.
 * This keeps capped kind selections full. Formats and in-code builders without objectives
 * are CSP; each format uses the same directive as its runner.
 */
internal object ProblemKind {
    /** True iff [ref] is a constraint *optimization* problem (carries an objective). */
    fun isCop(ref: ProblemRef): Boolean = when (ref.format) {
        Format.DIMACS, Format.JSON_SCHEMA, Format.IN_CODE -> false
        Format.WCNF -> true
        Format.SMTLIB -> runCatching {
            SmtObjective.read(CorpusFetcher.resolve(ref.source)) != null
        }.getOrDefault(false)
        Format.MPS -> runCatching {
            MpsObjective.read(CorpusFetcher.resolve(ref.source)) != null
        }.getOrDefault(false)
        Format.MINIZINC -> runCatching {
            FlatZincObjective.read(CorpusFetcher.resolve(ref.source)) != null
        }.getOrDefault(false)

        else -> {
            val text = runCatching { CorpusFiles.readText(CorpusFetcher.resolve(ref.source)) }.getOrNull()
            when {
                text == null -> false
                ref.format == Format.OPB -> OPB_OBJECTIVE.containsMatchIn(text)
                ref.format == Format.XCSP3 -> XCSP_OBJECTIVE.containsMatchIn(text)
                else -> false
            }
        }
    }

    private val OPB_OBJECTIVE = Regex("""(?m)^\s*(?:min|max):""")
    private val XCSP_OBJECTIVE = Regex("""<objective|type\s*=\s*"COP"""")
}
