package com.eignex.klause.bench.source

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CorpusMirrorTest {
    private val mirror = CorpusMirror(URI("https://cache.invalid/corpus-packs"))

    @Test
    fun `an existing collection pack is restored without consulting its original source`() {
        val root = Files.createTempDirectory("corpusmirror").toFile()
        try {
            val archive = archive(root, "fixture/nested/model.opb", "model")
            val destination = File(root, "collection")

            mirror.extract("fixture", archive, destination)

            assertEquals("model", File(destination, "nested/model.opb").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["../escape", "/escape", "other/model", "fixture/../../escape"])
    fun `a pack cannot escape its collection`(path: String) {
        val root = Files.createTempDirectory("corpusmirror").toFile()
        try {
            val archive = archive(root, path, "untrusted")

            assertFailsWith<IllegalArgumentException> { mirror.extract("fixture", archive, File(root, "collection")) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a pack cannot introduce filesystem links`() {
        val root = Files.createTempDirectory("corpusmirror").toFile()
        try {
            val archive = File(root, "pack.tar")
            TarArchiveOutputStream(archive.outputStream()).use { tar ->
                val entry = TarArchiveEntry("fixture/link", '2'.code.toByte())
                entry.linkName = "/outside"
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            }

            assertFailsWith<IllegalArgumentException> { mirror.extract("fixture", archive, File(root, "collection")) }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archive(root: File, path: String, text: String): File {
        val archive = File(root, "pack.tar")
        TarArchiveOutputStream(archive.outputStream()).use { tar ->
            val entry = TarArchiveEntry(path, true)
            entry.size = text.toByteArray().size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(text.toByteArray())
            tar.closeArchiveEntry()
        }
        return archive
    }
}
