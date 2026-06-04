package com.eignex.klause.cli

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig

/**
 * CLI logger for the `-v` (verbose) flag, backed by Kermit (KMP).
 *
 * Everything goes to **stderr** — stdout is reserved for the FZN/XCSP solution protocol —
 * prefixed with `% ` so the lines read as comments to anything scraping solver output.
 * With `-v` off the logger's minimum severity is [Severity.Assert] and nothing we emit
 * (all [Logger.v]/[Logger.i]) passes the filter, so the hot paths stay silent and cheap.
 */
private class StderrCommentWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        errPrintln("% $message")
        throwable?.let { errPrintln("% ${it.stackTraceToString()}") }
    }
}

internal fun cliLogger(verbose: Boolean): Logger = Logger(
    config = StaticConfig(
        minSeverity = if (verbose) Severity.Verbose else Severity.Assert,
        logWriterList = listOf(StderrCommentWriter()),
    ),
    tag = "klause-cli",
)
