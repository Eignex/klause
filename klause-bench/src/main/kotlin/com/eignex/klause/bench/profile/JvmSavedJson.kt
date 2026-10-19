package com.eignex.klause.bench.profile

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal object JvmSavedJson {
    enum class Kind(val maximumBytes: Long) {
        CATALOG_METADATA(16L * 1024 * 1024),
        PRODUCER_COMPLETION(128L * 1024 * 1024 + 1),
    }

    fun read(file: File, kind: Kind): JsonElement {
        val path = file.toPath()
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "JSON metadata is not a regular file" }
        require(file.length() <= kind.maximumBytes) { "JSON metadata exceeds ${kind.name} bound" }
        val output = ByteArrayOutputStream(minOf(file.length(), 64 * 1024L).toInt())
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { source ->
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                bytes += count
                require(bytes <= kind.maximumBytes) { "JSON metadata exceeds ${kind.name} bound" }
                output.write(buffer, 0, count)
            }
        }
        val text = output.toByteArray().decodeToString(throwOnInvalidSequence = true)
        return parse(text)
    }

    fun parse(text: String): JsonElement {
        rejectDuplicateKeys(text)
        return Reports.json.parseToJsonElement(text)
    }

    fun rejectDuplicateKeys(text: String) {
        val frames = ArrayDeque<SavedJsonObjectKeys?>()
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{' -> frames.addLast(SavedJsonObjectKeys())

                '[' -> frames.addLast(null)

                '}', ']' -> if (frames.isNotEmpty()) frames.removeLast()

                ',' -> frames.lastOrNull()?.expectKey = true

                '"' -> {
                    val start = index++
                    while (index < text.length && text[index] != '"') {
                        if (text[index] == '\\') index++
                        index++
                    }
                    require(index < text.length)
                    val frame = frames.lastOrNull()
                    if (frame != null && frame.expectKey) {
                        val key = Reports.json.parseToJsonElement(text.substring(start, index + 1))
                            .jsonPrimitive.content
                        require(frame.keys.add(key)) { "Duplicate saved JSON key" }
                        frame.expectKey = false
                    }
                }
            }
            require(frames.size <= 4096) { "Saved JSON nesting exceeds bounds" }
            index++
        }
    }
}

private class SavedJsonObjectKeys(val keys: HashSet<String> = HashSet(), var expectKey: Boolean = true)
