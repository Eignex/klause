package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.formats.mps.Mps

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

        else -> {
            val text = runCatching { CorpusFiles.readText(CorpusFetcher.resolve(ref.source)) }.getOrNull()
            when {
                text == null -> false
                ref.format == Format.MINIZINC -> hasSolveObjective(text)
                ref.format == Format.OPB -> OPB_OBJECTIVE.containsMatchIn(text)
                ref.format == Format.SMTLIB -> SMT_OBJECTIVE.containsMatchIn(text)
                ref.format == Format.XCSP3 -> XCSP_OBJECTIVE.containsMatchIn(text)
                ref.format == Format.MPS -> mpsHasObjective(text)
                else -> false
            }
        }
    }

    /** MPS declares a mandatory objective (`N`) row, but its column coefficients may all be absent — a
     *  pure feasibility model. Parsing is the honest test; a non-empty objective term list means a COP. */
    private fun mpsHasObjective(text: String): Boolean =
        runCatching { Mps.parse(text).objective.indices.isNotEmpty() }.getOrDefault(false)

    /** A `solve minimize|maximize …;` item, ignoring `%` line comments (MiniZinc/FlatZinc). The
     *  solve item routinely spans multiple lines — `solve :: int_search(…) \n minimize obj;` — so
     *  the match runs over the comment-stripped text *joined back together*, not line by line
     *  (the regex's `[^;]*` already spans newlines and stops at the item's terminating `;`). */
    internal fun hasSolveObjective(text: String): Boolean {
        val stripped = text.lineSequence().joinToString("\n") { it.substringBefore('%') }
        return SOLVE_OBJECTIVE.containsMatchIn(stripped)
    }

    private val SOLVE_OBJECTIVE = Regex("""\bsolve\b[^;]*\b(?:minimize|maximize)\b""")
    private val OPB_OBJECTIVE = Regex("""(?m)^\s*(?:min|max):""")
    private val SMT_OBJECTIVE = Regex("""\((?:minimize|maximize)\b""")
    private val XCSP_OBJECTIVE = Regex("""<objective|type\s*=\s*"COP"""")
}
