package com.eignex.klause.bench.source

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CorpusZipCollectionTest {
    @Test
    fun `a corrupted selected member fails its archive checksum before publication`() {
        val original = "NAME ORIGINAL\nENDATA\n".encodeToByteArray()
        val name = "source.mps"
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = original.size.toLong()
                    compressedSize = size
                    crc = CRC32().also { it.update(original) }.value
                })
                zip.write(original)
                zip.closeEntry()
            }
        }.toByteArray()
        archive[30 + name.length] = 'X'.code.toByte()
        val downloads = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                private val interval get() = getRequestProperty("Range").removePrefix("bytes=").split('-')
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes ${interval[0]}-${interval[1]}/${archive.size}"
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getContentLengthLong() = -1L
                override fun getInputStream() = archive.copyOfRange(
                    interval[0].toInt(), interval[1].toInt() + 1,
                ).inputStream()
            }
        })
        val root = Files.createTempDirectory("zipcollection").toFile()
        try {
            assertFailsWith<IllegalArgumentException> {
                CorpusZipCollection(URI("https://corpus.invalid/collection.zip"), "mps", 1024, downloads)
                    .fetch("source.mps.zst", root)
            }
            assertEquals(emptyList(), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cold discovery and selection fetch only eligible selected member bodies`() {
        val original = "NAME NEOS\nROWS\n N OBJECTIVE\nENDATA\n".encodeToByteArray()
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(original) } }.toByteArray()
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                val unrelated = ByteArray(524288)
                zip.putNextEntry(ZipEntry("unrelated").apply {
                    method = ZipEntry.STORED
                    size = unrelated.size.toLong()
                    compressedSize = size
                    crc = CRC32().also { it.update(unrelated) }.value
                })
                zip.write(unrelated)
                zip.closeEntry()
                for ((name, bytes) in listOf("nested/neos.mps.gz" to gzip, "other.mps" to original, "oversized.mps.gz" to ByteArray(2048))) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        var transferred = 0
        val downloads = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                private val interval get() = getRequestProperty("Range").removePrefix("bytes=").split('-')
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes ${interval[0]}-${interval[1]}/${archive.size}"
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getContentLengthLong() = -1L
                override fun getInputStream() = archive.copyOfRange(
                    interval[0].toInt(), interval[1].toInt() + 1,
                ).also { transferred += it.size }.inputStream()
            }
        })
        val root = Files.createTempDirectory("zipcollection").toFile()
        try {
            val collection = CorpusZipCollection(URI("https://corpus.invalid/collection.zip"), "mps", 1024, downloads)
            assertEquals(listOf("nested/neos.mps.zst", "other.mps.zst"), collection.paths)

            val selected = collection.fetch("nested/neos.mps.zst", root)

            assertContentEquals(original, CorpusFiles.open(selected).use { it.readBytes() })
            assertEquals(listOf("nested/neos.mps.zst"), root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList())
            assertTrue(transferred < archive.size / 2, "unselected archive bodies must remain unfetched")
        } finally {
            root.deleteRecursively()
        }
    }
}
