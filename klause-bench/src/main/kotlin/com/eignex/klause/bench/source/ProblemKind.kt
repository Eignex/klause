package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef

/**
 * Cheap COP/CSP classifier for the `kind` selection filter. It reads only the **objective
 * directive** from an instance's source — without compiling a MiniZinc model or constructing a
 * klause `Problem` — so `kind` can be applied *before* sampling. (Resolving the whole corpus to
 * classify first would be prohibitive; sampling first and filtering after would under-fill a
 * capped `kind=cop`/`kind=csp` selection.)
 *
 * The directive scanned here is the same one each runner turns into the resolved objective —
 * MiniZinc/FlatZinc `solve minimize|maximize`, OPB `min:`/`max:`, SMT-LIB `(minimize|(maximize`,
 * XCSP3 `<objective>` / `type="COP"` — so the verdict matches the resolved problem for any
 * well-formed instance. Formats with no objective notion (DIMACS, JSON schema) and in-code
 * builders (which never carry an objective) are always CSP.
 */
internal object ProblemKind {
    /** True iff [ref] is a constraint *optimization* problem (carries an objective directive). */
    fun isCop(ref: ProblemRef): Boolean = when (ref.format) {
        Format.DIMACS, Format.JSON_SCHEMA, Format.IN_CODE -> false

        else -> {
            val text = runCatching { CorpusFetcher.resolve(ref.source).readText() }.getOrNull()
            when {
                text == null -> false
                ref.format == Format.MINIZINC -> hasSolveObjective(text)
                ref.format == Format.OPB -> OPB_OBJECTIVE.containsMatchIn(text)
                ref.format == Format.SMTLIB_QF_LIA -> SMT_OBJECTIVE.containsMatchIn(text)
                ref.format == Format.XCSP3 -> XCSP_OBJECTIVE.containsMatchIn(text)
                else -> false
            }
        }
    }

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
