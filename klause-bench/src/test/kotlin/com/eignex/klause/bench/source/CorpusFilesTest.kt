package com.eignex.klause.bench.source

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class CorpusFilesTest {
    @ParameterizedTest
    @CsvSource("false, 8192", "true, 8192", "false, 65536", "true, 65536")
    fun `staging cancellation removes partial output before returning`(interrupt: Boolean, bufferBytes: Int) {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeBytes(ByteArray(32 * 1024)) })
            val staging = File(root, "staging")
            var partial: File? = null
            var cleanupNanos: Long? = null

            assertFailsWith<InterruptedIOException> {
                CorpusFiles.withPlainFile(packed, checkActive = {
                    val output = staging.walkTopDown().firstOrNull { it.isFile && it.length() > 0L }
                    if (output != null) {
                        partial = output
                        if (interrupt) Thread.currentThread().interrupt()
                        else throw InterruptedIOException("cancelled")
                    }
                }, onCleanup = { cleanupNanos = it }, stagingDirectory = staging,
                    bufferBytes = bufferBytes) { error("cancelled source") }
            }

            assertTrue(partial != null)
            assertFalse(requireNotNull(partial).exists())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertTrue(requireNotNull(cleanupNanos) >= 0L)
            assertEquals(interrupt, Thread.currentThread().isInterrupted)
            assertTrue(packed.isFile)
        } finally {
            Thread.interrupted()
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [8192, 65536])
    fun `bounded staging chunks preserve all source bytes`(bufferBytes: Int) {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val body = ByteArray(130_003) { (it % 251).toByte() }
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeBytes(body) })

            val observed = CorpusFiles.withPlainFile(packed, stagingDirectory = File(root, "staging"),
                bufferBytes = bufferBytes) { it.readBytes() }

            assertContentEquals(body, observed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed cleanup observation still releases the staged source`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeText("p cnf 0 0\n") })
            val staging = File(root, "staging")

            CorpusFiles.withPlainFile(packed, stagingDirectory = staging,
                onCleanupStart = { error("observation unavailable") }) { assertTrue(it.isFile) }

            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed decoding removes the staging directory`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val packed = File(root, "inst.cnf.zst").apply { writeText("invalid zstd") }
            val staging = File(root, "staging")

            assertFailsWith<IOException> {
                CorpusFiles.withPlainFile(packed, stagingDirectory = staging) { error("invalid source") }
            }

            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed consumers release their staged source`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeText("p cnf 0 0\n") })
            val staging = File(root, "staging")

            assertFailsWith<IllegalStateException> {
                CorpusFiles.withPlainFile(packed, stagingDirectory = staging) { error("consumer failed") }
            }

            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unpacking can retain the original compressed cache object`(keepPacked: Boolean) {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val body = "p cnf 2 1\n1 -2 0\n"
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeText(body) })

            val plain = CorpusFiles.unpack(packed, keepPacked)

            assertEquals(body, plain.readText())
            assertEquals(keepPacked, packed.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a truncated compressed instance fails instead of returning partial source`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeText("p cnf 2 1\n1 -2 0\n") })
            RandomAccessFile(packed, "rw").use { it.setLength(packed.length() - 1) }

            assertFailsWith<IOException> { CorpusFiles.readText(packed) }
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun `readText should return the plain text of a compressed instance`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            val body = "p cnf 2 1\n1 -2 0\n"
            val packed = CorpusFiles.compress(File(root, "inst.cnf").apply { writeText(body) })

            assertEquals(body, CorpusFiles.readText(packed))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `compressTree should compress instances in place and leave MiniZinc sources plain`() {
        val root = Files.createTempDirectory("corpusfiles").toFile()
        try {
            File(root, "fam").mkdirs()
            File(root, "fam/inst.mps").writeText("NAME inst\nROWS\n N obj\nCOLUMNS\nRHS\nENDATA\n")
            File(root, "fam/model.mzn").writeText("var 1..3: x;\nsolve satisfy;\n")
            File(root, "fam/data.dzn").writeText("n = 3;\n")

            val done = CorpusFiles.compressTree(root)

            assertEquals(1, done.files)
            assertFalse(File(root, "fam/inst.mps").exists())
            assertTrue(File(root, "fam/inst.mps.zst").isFile)
            assertTrue(File(root, "fam/model.mzn").isFile)
            assertTrue(File(root, "fam/data.dzn").isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
