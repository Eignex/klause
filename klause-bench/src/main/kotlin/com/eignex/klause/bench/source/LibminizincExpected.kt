package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.Expected
import java.io.File

// Best effort: newer files carry a YAML `/*** !Test ... ***/` block with `status:` and sometimes `objective:`, older
// ones `% expected:` comments. No directive means Unknown; the file is then only a compile/parse exercise.
internal object LibminizincExpected {
    private val statusRe = Regex("""status\s*:\s*([A-Z_]+)""")
    private val objectiveRe = Regex("""objective\s*:\s*(-?\d+)""")

    fun parse(mzn: File): Expected {
        val text = runCatching { mzn.readText() }.getOrNull() ?: return Expected.Unknown
        // Only the top-of-file directive region, to avoid matching model content.
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
