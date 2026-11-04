package com.eignex.klause.bench.report

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The bench's JSON encoder and writer, shared by solve records and profile outputs. */
object Reports {
    internal fun fileStem(name: String): String = buildString {
        for (character in name) {
            when {
                character == '/' -> append('_')
                character in "\\:%" || character.isISOControl() ->
                    append('%').append(character.code.toString(16).padStart(2, '0'))
                else -> append(character)
            }
        }
    }

    internal val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Ignore unrecognized fields so persisted records with additional metadata remain readable.
        ignoreUnknownKeys = true
    }

    internal inline fun <reified T> writeJson(path: String, value: T) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(value))
        println()
        println("wrote $path")
    }

    internal fun readGitSha(): String? = runCatching {
        val proc = ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()
}
