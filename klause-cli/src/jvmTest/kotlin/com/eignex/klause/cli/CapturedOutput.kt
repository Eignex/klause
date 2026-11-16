package com.eignex.klause.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream

internal fun captureStdout(block: () -> Unit): String = captureOutput(System.out, System::setOut, block)

internal fun captureStderr(block: () -> Unit): String = captureOutput(System.err, System::setErr, block)

private fun captureOutput(previous: PrintStream, install: (PrintStream) -> Unit, block: () -> Unit): String {
    val bytes = ByteArrayOutputStream()
    install(PrintStream(bytes))
    try {
        block()
    } finally {
        install(previous)
    }
    return bytes.toString()
}
