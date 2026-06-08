package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Expected
import java.io.File

/**
 * Best-effort parser for the test directives libminizinc embeds in its `.mzn` test files.
 * Modern files carry a YAML `/*** !Test ... ***/` block with a `status:` (and sometimes an
 * `objective:`); older files use `% expected:` style comments. We scan for the recognizable
 * keywords and map to the catalog [Expected] oracle, defaulting to [Expected.Unknown] when no
 * directive is found (the file is then merely a compile/parse exercise).
 */
internal object LibminizincExpected {
    private val statusRe = Regex("""status\s*:\s*([A-Z_]+)""")
    private val objectiveRe = Regex("""objective\s*:\s*(-?\d+)""")

    fun parse(mzn: File): Expected {
        val text = runCatching { mzn.readText() }.getOrNull() ?: return Expected.Unknown
        // Only look at the directive region (top-of-file comment block) to avoid matching
        // model content; fall back to the whole file if no block delimiter is present.
        val header = text.substringBefore("***/").take(4000) + text.take(1000)
        val upper = header.uppercase()
        return when {
            "UNSATISFIABLE" in upper -> Expected.Unsat

            statusRe.find(header)?.groupValues?.get(1)?.let { it.contains("UNSAT") } == true -> Expected.Unsat

            "OPTIMAL_SOLUTION" in upper -> objectiveRe.find(header)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { Expected.Opt(it) } ?: Expected.Sat

            "SATISFIABLE" in upper || "SATISFIED" in upper -> Expected.Sat

            else -> Expected.Unknown
        }
    }
}
