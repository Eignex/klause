@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtSessionSink
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite

internal actual fun openSmtSessionAppend(path: String): SmtSessionSink {
    val file = fopen(path, "ab") ?: throw UnsupportedSmtException("cannot open output channel '$path'")
    return object : SmtSessionSink {
        override fun write(text: String) {
            val bytes = "$text\n".encodeToByteArray()
            val written = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), file) }
            if (written != bytes.size.toULong() || fflush(file) != 0) {
                throw UnsupportedSmtException("cannot write output channel '$path'")
            }
        }
        override fun close() {
            fclose(file)
        }
    }
}
