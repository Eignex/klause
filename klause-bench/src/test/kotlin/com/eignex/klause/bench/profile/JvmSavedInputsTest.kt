package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSavedInputsTest {
    @Test
    fun `whole archive authentication preserves all member bytes`(@TempDir out: File) {
        val members = linkedMapOf("a/completion.json" to "{}\n", "b/opaque.txt" to "cold\r\n")
        val archive = File(out, "input.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(archive.outputStream())).use { tar ->
            members.forEach { (name, text) ->
                val raw = text.encodeToByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = raw.size.toLong() })
                tar.write(raw)
                tar.closeArchiveEntry()
            }
        }
        val files = JsonObject(members.mapValues { (_, text) -> JvmSavedReports.identity(text.encodeToByteArray()) })
        val directory = File(out, "staged").apply { mkdir() }

        val paths = JvmSavedInputs(out, out).extract(archive, files, directory)

        assertEquals(members, paths.mapValues { (_, path) -> path.readText() })
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate", "missing", "unknown", "hash", "link"])
    fun `a partial linked or altered archive cannot satisfy its whole manifest`(variant: String, @TempDir out: File) {
        val raw = "{}\n".encodeToByteArray()
        val archive = File(out, "input.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(archive.outputStream())).use { tar ->
            val name = if (variant == "unknown") "other.json" else "completion.json"
            val entry = if (variant == "link") {
                TarArchiveEntry(name, '2'.code.toByte()).apply {
                linkName = "external.json"
            }
            } else {
                TarArchiveEntry(name).apply { size = raw.size.toLong() }
            }
            tar.putArchiveEntry(entry)
            if (variant != "link") tar.write(raw)
            tar.closeArchiveEntry()
            if (variant == "duplicate") {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = raw.size.toLong() })
                tar.write(raw)
                tar.closeArchiveEntry()
            }
        }
        val files = buildJsonObject {
            put(
                "completion.json",
                if (variant == "hash") {
                    JsonObject(JvmSavedReports.identity(raw) + ("sha256" to JsonPrimitive("a".repeat(64))))
                } else {
                    JvmSavedReports.identity(raw)
                },
            )
            if (variant == "missing") put("absent.json", JvmSavedReports.identity(raw))
        }
        val directory = File(out, "staged").apply { mkdir() }

        assertFailsWith<IllegalArgumentException> { JvmSavedInputs(out, out).extract(archive, files, directory) }
    }
}
