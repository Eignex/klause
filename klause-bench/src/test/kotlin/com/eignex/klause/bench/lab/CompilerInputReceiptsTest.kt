package com.eignex.klause.bench.lab

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerInputReceiptsTest {
    @Test
    fun `fresh consumers reuse unchanged compiler bytes and detect restored mtimes and replacements`(
        @TempDir root: Path,
    ) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        val file = compiler.resolve("tool")
        Files.writeString(file, "original")
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        var reads = 0
        repeat(2) {
            val receipts = CompilerInputReceipts(cache, hash = { path -> reads++; LabInputs.treeHash(path) })
            assertTrue(receipts.matches(compiler, expected))
        }
        assertEquals(1, reads)
        val modified = Files.getLastModifiedTime(file)
        Files.writeString(file, "modified")
        Files.setLastModifiedTime(file, modified)
        assertFalse(CompilerInputReceipts(cache).matches(compiler, expected))
        val replacement = root.resolve("replacement")
        Files.writeString(replacement, "original")
        Files.setLastModifiedTime(replacement, modified)
        Files.move(replacement, file, REPLACE_EXISTING)
        val receipts = CompilerInputReceipts(cache, hash = { path -> reads++; LabInputs.treeHash(path) })
        assertTrue(receipts.matches(compiler, expected))
        assertEquals(2, reads)
    }

    @Test
    fun `directory membership corrupt receipts and mismatched claims cannot reuse an old identity`(
        @TempDir root: Path,
    ) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        Files.writeString(compiler.resolve("tool"), "tool")
        val expected = LabInputs.treeHash(compiler)
        val cache = root.resolve("receipts")
        assertTrue(CompilerInputReceipts(cache).matches(compiler, expected))
        val added = Files.createDirectories(compiler.resolve("nested")).resolve("added")
        Files.writeString(added, "changed")
        assertFalse(CompilerInputReceipts(cache).matches(compiler, expected))
        Files.delete(added)
        assertTrue(CompilerInputReceipts(cache).matches(compiler, expected))
        val receipt = Files.list(cache).use { it.findFirst().orElseThrow() }
        Files.writeString(receipt, "broken")
        var reads = 0
        val receipts = CompilerInputReceipts(cache, hash = { path -> reads++; LabInputs.treeHash(path) })
        assertTrue(receipts.matches(compiler, expected))
        assertEquals(1, reads)
        assertFalse(CompilerInputReceipts(cache).matches(compiler, "0".repeat(64)))
        Files.delete(compiler.resolve("tool"))
        assertFalse(CompilerInputReceipts(cache).matches(compiler, expected))
    }

    @Test
    fun `unreliable metadata hashes every time and mutation during capture cannot publish a receipt`(
        @TempDir root: Path,
    ) {
        val compiler = Files.createDirectory(root.resolve("compiler"))
        val file = compiler.resolve("tool")
        Files.writeString(file, "original")
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
        assertFalse(CompilerInputReceipts(cache, hash = {
            LabInputs.treeHash(it).also {
                val modified = Files.getLastModifiedTime(file)
                Files.writeString(file, "modified")
                Files.setLastModifiedTime(file, modified)
            }
        }).matches(compiler, expected))
        assertFalse(Files.exists(cache))
    }
}
