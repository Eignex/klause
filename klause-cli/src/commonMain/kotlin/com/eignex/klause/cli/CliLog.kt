package com.eignex.klause.cli

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.eignex.util.process.printErrorLine

/**
 * CLI logger for the `-v` (verbose) flag, backed by Kermit (KMP).
 *
 * Lines use the invocation diagnostic destination, prefixed with `% `.
 * With `-v` off the logger's minimum severity is [Severity.Assert] and nothing we emit
 * (all [Logger.v]/[Logger.i]) passes the filter, so the hot paths stay silent and cheap.
 */
private class DiagnosticCommentWriter(private val emit: (String) -> Unit) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        emit("% $message")
        throwable?.let { emit("% ${it.stackTraceToString()}") }
    }
}

internal fun cliLogger(verbose: Boolean, emit: (String) -> Unit = ::printErrorLine): Logger = Logger(
    config = StaticConfig(
        minSeverity = if (verbose) Severity.Verbose else Severity.Assert,
        logWriterList = listOf(DiagnosticCommentWriter(emit)),
    ),
    tag = "klause-cli",
)
