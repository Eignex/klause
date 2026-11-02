package com.eignex.klause.bench.lab

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerInputReceiptsTest {
    @Test
    fun `fresh consumers reuse unchanged compiler bytes`(@TempDir root: Path) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        Files.writeString(compiler.resolve("tool"), "original")
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        var reads = 0
        repeat(2) {
            val receipts = CompilerInputReceipts(cache, hash = { path -> reads++; LabInputs.treeHash(path) })
            assertTrue(receipts.matches(compiler, expected))
        }
        assertEquals(1, reads)
    }

    @ParameterizedTest
    @CsvSource("write,false", "replace,true", "add,false", "delete,false")
    fun `changed file identities and membership require byte verification`(
        change: String,
        matches: Boolean,
        @TempDir root: Path,
    ) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        val file = compiler.resolve("tool")
        Files.writeString(file, "original")
        val modified = Files.getLastModifiedTime(file)
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        assertTrue(CompilerInputReceipts(cache).matches(compiler, expected))
        when (change) {
            "write" -> {
                Files.writeString(file, "modified")
                Files.setLastModifiedTime(file, modified)
            }
            "replace" -> {
                val replacement = root.resolve("replacement")
                Files.writeString(replacement, "original")
                Files.setLastModifiedTime(replacement, modified)
                Files.move(replacement, file, REPLACE_EXISTING)
            }
            "add" -> Files.writeString(Files.createDirectories(compiler.resolve("nested")).resolve("added"), "new")
            "delete" -> Files.delete(file)
            else -> error(change)
        }
        var reads = 0
        val receipts = CompilerInputReceipts(cache, hash = { path -> reads++; LabInputs.treeHash(path) })
        assertEquals(matches, receipts.matches(compiler, expected))
        assertEquals(1, reads)
    }

    @Test
    fun `corrupt receipts fall back to byte verification`(@TempDir root: Path) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        Files.writeString(compiler.resolve("tool"), "tool")
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        assertTrue(CompilerInputReceipts(cache).matches(compiler, expected))
        val receipt = Files.list(cache).use { it.findFirst().orElseThrow() }
        Files.writeString(receipt, "broken")
        var reads = 0
        val receipts = CompilerInputReceipts(cache, hash = { path -> reads++; LabInputs.treeHash(path) })
        assertTrue(receipts.matches(compiler, expected))
        assertEquals(1, reads)
    }

    @Test
    fun `a cached identity cannot validate a different claim`(@TempDir root: Path) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        Files.writeString(compiler.resolve("tool"), "tool")
        val cache = root.resolve("receipts")
        assertTrue(CompilerInputReceipts(cache).matches(compiler, LabInputs.treeHash(compiler)))
        assertFalse(CompilerInputReceipts(cache).matches(compiler, "0".repeat(64)))
    }

    @Test
    fun `unreliable metadata hashes every time`(@TempDir root: Path) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        Files.writeString(compiler.resolve("tool"), "original")
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        var reads = 0
        repeat(2) {
            val receipts = CompilerInputReceipts(
                cache, hash = { path -> reads++; LabInputs.treeHash(path) }, reliable = { false },
            )
            assertTrue(receipts.matches(compiler, expected))
        }
        assertEquals(2, reads)
        assertFalse(Files.exists(cache))
    }

    @Test
    fun `mutation during capture cannot publish a receipt`(@TempDir root: Path) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        val file = compiler.resolve("tool")
        Files.writeString(file, "original")
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        assertFalse(CompilerInputReceipts(cache, hash = { path ->
            LabInputs.treeHash(path).also {
                val modified = Files.getLastModifiedTime(file)
                Files.writeString(file, "modified")
                Files.setLastModifiedTime(file, modified)
            }
        }).matches(compiler, expected))
        assertFalse(Files.exists(cache))
    }
}
