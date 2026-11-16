package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtSessionSink
import com.eignex.klause.formats.smtlib.UnsupportedSmtException
import java.io.BufferedWriter
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter

internal actual fun openSmtSessionAppend(path: String): SmtSessionSink {
    val writer = try {
        BufferedWriter(OutputStreamWriter(FileOutputStream(path, true), Charsets.UTF_8))
    } catch (error: IOException) {
        throw UnsupportedSmtException("cannot open output channel '$path': ${error.message}").apply {
            initCause(error)
        }
    }
    return object : SmtSessionSink {
        override fun write(text: String) {
            writer.write("$text\n")
            writer.flush()
        }
        override fun close() = writer.close()
    }
}
