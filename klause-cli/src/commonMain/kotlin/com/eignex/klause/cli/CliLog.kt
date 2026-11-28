package com.eignex.klause.cli

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.eignex.util.process.printErrorLine

// With `-v` off the minimum severity is Assert, so nothing the CLI emits passes and hot paths stay cheap.
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
