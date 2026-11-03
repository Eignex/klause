package com.eignex.klause.cli

import com.github.luben.zstd.ZstdOutputStreamNoFinalizer
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlatformTest {

    @Test
    fun `in process zstd input preserves characters across source chunks`() {
        val file = File.createTempFile("klause-jni", ".cnf.zst")
        val property = "klause.zstd.decoder"
        val previous = System.getProperty(property)
        try {
            val body = "c \u03b1\u03b2\u03b3\n".repeat(12_000) + "p cnf 1 1\n1 0\n"
            ZstdOutputStreamNoFinalizer(file.outputStream(), 1).use { it.write(body.toByteArray()) }
            System.setProperty(property, "jni")

            val observed = readTextFile(file.absolutePath)

            assertEquals(body, observed)
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            file.delete()
        }
    }

    @Test
    fun `in process zstd input rejects a malformed frame`() {
        val file = File.createTempFile("klause-jni", ".cnf.zst")
        val property = "klause.zstd.decoder"
        val previous = System.getProperty(property)
        try {
            file.writeText("p cnf 1 1\n1 0\n")
            System.setProperty(property, "jni")

            assertFailsWith<IOException> { readTextFile(file.absolutePath) }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            file.delete()
        }
    }

    @Test
    fun `in process zstd input stops producing chunks after early close`() {
        val file = File.createTempFile("klause-jni", ".cnf.zst")
        val property = "klause.zstd.decoder"
        val previous = System.getProperty(property)
        try {
            ZstdOutputStreamNoFinalizer(file.outputStream(), 1).use { it.write("c comment\n".repeat(20_000).toByteArray()) }
            System.setProperty(property, "jni")
            val source = openFileSource(file.absolutePath)

            source.useSource { assertNotNull(it.next()) }

            assertNull(source.next())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            file.delete()
        }
    }

    @Test
    fun `the format extension should be taken after any compression suffix`() {
        assertEquals("cnf", fileExtension("foo.cnf"))
        assertEquals("cnf", fileExtension("dir/foo.cnf.xz"))
        assertEquals("xml", fileExtension("foo.xml.gz"))
        assertEquals("opb", fileExtension("foo.opb.bz2"))
        assertEquals("mps", fileExtension("foo.mps.zst"))
        assertEquals("", fileExtension("noext"))
    }

    @Test
    fun `the compression suffix should be recognised only for known compressors`() {
        assertEquals("xz", compressionExtension("foo.cnf.xz"))
        assertEquals("gz", compressionExtension("foo.cnf.gz"))
        assertEquals("zst", compressionExtension("foo.cnf.zst"))
        assertNull(compressionExtension("foo.cnf"))
        assertNull(compressionExtension("foo.tar"))
    }

    @Test
    fun `readTextFile should decompress a gzip-compressed instance`() {
        val plain = File.createTempFile("klause-cmp", ".cnf").apply { deleteOnExit() }
        val body = "p cnf 1 1\n1 0\n"
        val gz = File(plain.absolutePath + ".gz").apply { deleteOnExit() }
        GZIPOutputStream(gz.outputStream()).bufferedWriter().use { it.write(body) }
        assertEquals(body, readTextFile(gz.absolutePath))
    }

    @Test
    fun `readTextFile should decompress a zstd-compressed instance`() {
        val plain = File.createTempFile("klause-cmp", ".cnf").apply { deleteOnExit() }
        val body = "p cnf 1 1\n1 0\n"
        plain.writeText(body)
        val zst = File(plain.absolutePath + ".zst").apply { deleteOnExit() }
        val zstd = ProcessBuilder("zstd", "-q", "-f", plain.absolutePath, "-o", zst.absolutePath).start()
        assertEquals(0, zstd.waitFor())
        assertEquals(body, readTextFile(zst.absolutePath))
    }
}
