package com.eignex.klause.cli

import com.eignex.util.process.printErrorLine

internal inline fun logVerbose(
    verbose: Boolean,
    emit: (String) -> Unit = ::printErrorLine,
    message: () -> String,
) {
    if (verbose) emit("% ${message()}")
}
