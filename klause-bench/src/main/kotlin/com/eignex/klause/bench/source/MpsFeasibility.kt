package com.eignex.klause.bench.source

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object MpsFeasibility {
    fun resolve(source: File): File {
        val transformed = transform(CorpusFiles.readText(source))
        val hash = MessageDigest.getInstance("SHA-256").digest(transformed.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(CorpusFetcher.cacheRoot, ".mps-feasibility").also { it.mkdirs() }
        return File(directory, "$hash.mps").also { file ->
            if (!file.isFile) {
                val pending = Files.createTempFile(directory.toPath(), "mps-", ".tmp")
                try {
                    Files.writeString(pending, transformed)
                    Files.move(pending, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(pending)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun transform(text: String): String {
        val lines = text.trimEnd('\n', '\r').lineSequence().toList()
        val freeRows = HashSet<String>()
        var section = ""
        var zeroRow: String? = null
        for (line in lines) {
            if (line.isBlank() || line.startsWith('*')) continue
            val fields = fields(line)
            if (!line.first().isWhitespace()) {
                section = fields.first()
            } else if (section == "ROWS") {
                if (fields.first() == "N") freeRows.add(fields[1]) else if (zeroRow == null) zeroRow = fields[1]
            }
        }
        // An objective-only column must retain its bounds and integrality after its coefficients are removed.
        val anchor = zeroRow ?: generateSequence("KLAUSE_ZERO") { "${it}_" }
            .first { name -> lines.none { fields(it).contains(name) } }
        val output = ArrayList<String>()
        section = ""
        for (line in lines) {
            if (line.isBlank() || line.startsWith('*')) {
                output.add(line)
                continue
            }
            val fields = fields(line)
            if (!line.first().isWhitespace()) {
                section = fields.first()
                if (section == "OBJSENSE" || section == "OBJNAME") continue
                output.add(line)
                if (section == "ROWS" && zeroRow == null) output.add(" E $anchor")
                continue
            }
            when (section) {
                "OBJSENSE", "OBJNAME" -> Unit
                "ROWS" -> if (fields.first() != "N") output.add(line)
                "COLUMNS" -> {
                    if (fields.any { it.trim('\'') == "MARKER" }) {
                        output.add(line)
                    } else {
                        val pairs = fields.drop(1).chunked(2).filter { it.first() !in freeRows }
                        output.add(" ${fields.first()} " + pairs.flatten().ifEmpty { listOf(anchor, "0") }.joinToString(" "))
                    }
                }
                "RHS", "RANGES" -> {
                    val namedSet = fields.size % 2 == 1
                    val pairs = fields.drop(if (namedSet) 1 else 0).chunked(2).filter { it.first() !in freeRows }
                    if (pairs.isNotEmpty()) {
                        val prefix = if (namedSet) listOf(fields.first()) else emptyList()
                        output.add(" " + (prefix + pairs.flatten()).joinToString(" "))
                    }
                }
                else -> output.add(line)
            }
        }
        return output.joinToString("\n", postfix = "\n")
    }

    private fun fields(line: String): List<String> = line.trim().split(WHITESPACE)
        .takeWhile { !it.startsWith('$') }

    private val WHITESPACE = Regex("\\s+")
}
