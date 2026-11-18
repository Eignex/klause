package com.eignex.klause.bench.source

import com.eignex.klause.ir.ObjectiveSense
import java.io.File
import java.io.InterruptedIOException

internal object MpsObjective {
    private val whitespace = Regex("\\s+")
    private val metadataSections = setOf("ROWS", "LAZYCONS", "OBJSENSE")

    fun read(file: File): ObjectiveSense? {
        checkActive()
        return CorpusFiles.open(file).bufferedReader().use { input ->
            var section = ""
            var hasObjective = false
            var sense = ObjectiveSense.MINIMIZE
            for (line in input.lineSequence()) {
                checkActive()
                if (line.isBlank() || line.startsWith('*')) continue
                val header = !line[0].isWhitespace()
                if (!header && section !in metadataSections) continue
                val fields = line.trim().split(whitespace).takeWhile { !it.startsWith('$') }
                if (fields.isEmpty()) continue
                if (header) {
                    section = fields[0].uppercase()
                    if (section == "ENDATA") break
                    if (section == "OBJSENSE" && isMaximize(fields.getOrNull(1))) sense = ObjectiveSense.MAXIMIZE
                } else if (section == "OBJSENSE") {
                    if (isMaximize(fields[0])) sense = ObjectiveSense.MAXIMIZE
                } else if (fields.size >= 2 && fields[0].equals("N", true)) {
                    hasObjective = true
                }
            }
            sense.takeIf { hasObjective }
        }
    }

    private fun isMaximize(token: String?): Boolean =
        token.equals("MAX", true) || token.equals("MAXIMIZE", true)

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("MPS objective scan interrupted")
    }
}
