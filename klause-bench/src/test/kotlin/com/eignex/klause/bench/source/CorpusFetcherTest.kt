package com.eignex.klause.bench.source

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

class CorpusFetcherTest {
    @Test
    fun `LZMA unpacking preserves original instance bytes`() {
        val root = Files.createTempDirectory("corpusfetcher").toFile()
        try {
            val packed = File(root, "instance.xml.lzma")
            val bytes = "<instance>original source</instance>\n".encodeToByteArray()
            LZMAOutputStream(packed.outputStream(), LZMA2Options(0), bytes.size.toLong()).use { it.write(bytes) }

            CorpusFetcher.unpackLzma(packed)

            assertContentEquals(bytes, File(root, "instance.xml").readBytes())
            assertFalse(packed.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ZIP extraction preserves nested instance bytes`() {
        val root = Files.createTempDirectory("corpusfetcher").toFile()
        try {
            val archive = File(root, "collection.zip")
            val bytes = "<instance>original source</instance>\n".encodeToByteArray()
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("family/instance.xml"))
                zip.write(bytes)
                zip.closeEntry()
            }

            CorpusFetcher.extractZip(archive, File(root, "collection"))

            assertContentEquals(bytes, File(root, "collection/family/instance.xml").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ZIP extraction rejects entries outside the collection`(absolute: Boolean) {
        val root = Files.createTempDirectory("corpusfetcher").toFile()
        try {
            val archive = File(root, "collection.zip")
            val outside = File(root, "outside.xml")
            val name = if (absolute) outside.absolutePath else "../outside.xml"
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }

            assertFailsWith<IllegalArgumentException> {
                CorpusFetcher.extractZip(archive, File(root, "collection"))
            }

            assertFalse(outside.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
