package com.eignex.klause.formats

/**
 * Split [this] into whitespace-separated non-empty tokens with a single character scan, shared by the
 * line-oriented format readers (DIMACS / OPB / MPS) in place of a per-reader `Regex("\\s+")`. Runs of
 * whitespace separate tokens; leading and trailing whitespace produce no empty tokens. Scanning by
 * [Char.isWhitespace] rather than a `\s` regex is deterministic across the Kotlin targets and needs no
 * compiled pattern per line.
 */
internal fun String.splitWhitespace(): List<String> {
    val out = ArrayList<String>()
    val n = length
    var i = 0
    while (i < n) {
        while (i < n && this[i].isWhitespace()) i++
        if (i >= n) break
        val start = i
        while (i < n && !this[i].isWhitespace()) i++
        out.add(substring(start, i))
    }
    return out
}
