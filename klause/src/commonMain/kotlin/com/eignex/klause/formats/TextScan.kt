package com.eignex.klause.formats

// A character scan rather than a `\s` regex is deterministic across Kotlin targets and compiles no pattern.
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
