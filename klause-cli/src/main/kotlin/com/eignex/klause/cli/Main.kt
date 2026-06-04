package com.eignex.klause.cli

/**
 * Unified klause CLI entry point: dispatches to the FlatZinc path ([runFzn]) or the
 * XCSP3 / SMT-LIB path ([runXcsp]) and forwards the full argument vector unchanged —
 * each path keeps its own flag parser and output protocol.
 *
 * Routing rules, in order:
 *  1. `--format` or `--coverage` anywhere in the args → XCSP path (FZN has neither flag).
 *  2. Positional input file with an `.xml` / `.xcsp` / `.xcsp3` / `.smt2` / `.smt`
 *     extension → XCSP path.
 *  3. Everything else → FZN path. The default MUST stay FZN: MiniZinc invokes this
 *     binary via the `klause.msc` wrapper with MiniZinc-standard flags and a `.fzn`
 *     file, and tolerating unknown future flags is part of that protocol.
 */
fun main(args: Array<String>) {
    if (routeToXcsp(args)) runXcsp(args) else runFzn(args)
}

/** Flags that consume the next argument, across BOTH parsers — needed to find the
 *  positional input path without misreading a flag value as the path. */
private val valueFlags = setOf(
    "-n", "-t", "--time-limit", "-r", "-e", "--engine", "--ozn",
    "--unbounded-int-lo", "--unbounded-int-hi", "--format", "-p", "--param",
)

private fun routeToXcsp(args: Array<String>): Boolean {
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--format" || a == "--coverage" -> return true

            a in valueFlags -> i++

            // skip the flag's value
            !a.startsWith("-") ->
                return a.substringAfterLast('.').lowercase() in
                    setOf("xml", "xcsp", "xcsp3", "smt2", "smt")
        }
        i++
    }
    return false
}
