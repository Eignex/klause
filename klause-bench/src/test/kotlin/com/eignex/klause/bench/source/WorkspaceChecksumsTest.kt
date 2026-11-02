package com.eignex.klause.bench.source

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkspaceChecksumsTest {
    @Test
    fun `fresh processes reuse unchanged file bytes`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        var reads = 0
        repeat(2) {
            WorkspaceChecksums(root, cache, hash = { reads++; digest(it) }).use { checksums ->
                assertEquals(digest(file), checksums.checksum(file))
            }
        }
        assertEquals(1, reads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["write", "replace"])
    fun `preserved modification time cannot hide changed bytes or inodes`(change: String, @TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        val modified = Files.getLastModifiedTime(file)
        WorkspaceChecksums(root, cache).use { it.checksum(file) }
        if (change == "write") {
            Files.writeString(file, "modified")
            Files.setLastModifiedTime(file, modified)
        } else {
            val replacement = Files.writeString(root.resolve("replacement"), "original")
            Files.setLastModifiedTime(replacement, modified)
            Files.move(replacement, file, REPLACE_EXISTING)
        }
        var reads = 0
        WorkspaceChecksums(root, cache, hash = { reads++; digest(it) }).use {
            assertEquals(digest(file), it.checksum(file))
        }
        assertEquals(1, reads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["corrupt", "oversize"])
    fun `invalid cache records require byte verification`(failure: String, @TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        WorkspaceChecksums(root, cache).use { it.checksum(file) }
        val receipt = Files.list(cache).use { files -> files.filter { it.toString().endsWith(".bin") }.findFirst().orElseThrow() }
        Files.write(receipt, if (failure == "corrupt") byteArrayOf(1) else ByteArray(1024 * 1024 + 1))
        var reads = 0
        WorkspaceChecksums(root, cache, hash = { reads++; digest(it) }).use {
            assertEquals(digest(file), it.checksum(file))
        }
        assertEquals(1, reads)
    }

    @Test
    fun `unsupported filesystems read the bytes without storing receipts`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        var reads = 0
        repeat(2) {
            WorkspaceChecksums(root, cache, hash = { reads++; digest(it) }, reliable = { false }).use {
                assertEquals(digest(file), it.checksum(file))
            }
        }
        assertEquals(2, reads)
        assertFalse(Files.exists(cache))
    }

    @Test
    fun `unavailable cache storage preserves fresh byte verification`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = Files.writeString(root.resolve("cache"), "not a directory")
        WorkspaceChecksums(root, cache).use { assertEquals(digest(file), it.checksum(file)) }
    }

    @Test
    fun `changes during hashing cannot publish or return a verified digest`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        assertFailsWith<IllegalArgumentException> {
            WorkspaceChecksums(root, cache, hash = { source ->
                digest(source).also { Files.writeString(source, "modified") }
            }).use { it.checksum(file) }
        }
        assertFalse(Files.exists(cache))
    }

    @Test
    fun `checksum failures retain their error rather than supplying a cache claim`(@TempDir root: Path) {
        val file = Files.writeString(root.resolve("input"), "original")
        assertFailsWith<java.io.IOException> {
            WorkspaceChecksums(root, root.resolve("cache"), hash = { throw java.io.IOException("read failed") }).use {
                it.checksum(file)
            }
        }
    }

    private fun digest(file: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
}
