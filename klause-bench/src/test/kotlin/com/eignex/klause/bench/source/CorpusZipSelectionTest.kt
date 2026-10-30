package com.eignex.klause.bench.source

import com.eignex.klause.bench.catalog.FetchMethod
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream

class CorpusZipSelectionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a selected member preserves original bytes without fetching unrelated member bodies`(xz: Boolean) {
        val bytes = "7 1 2 0\n11 -1 2 0\n".encodeToByteArray()
        val name = "source.wcnf" + if (xz) ".xz" else ""
        val packed = if (xz) ByteArrayOutputStream().also { out ->
            XZOutputStream(out, LZMA2Options(0)).use { it.write(bytes) }
        }.toByteArray() else bytes
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                val unrelated = ByteArray(262144)
                zip.putNextEntry(ZipEntry("unrelated").apply {
                    method = ZipEntry.STORED
                    size = unrelated.size.toLong()
                    compressedSize = size
                    crc = CRC32().also { it.update(unrelated) }.value
                })
                zip.write(unrelated)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("nested/$name"))
                zip.write(packed)
                zip.closeEntry()
            }
        }.toByteArray()
        var transferred = 0
        val downloader = CorpusDownload(open = { uri ->
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
        val member = FetchMethod.ZipMember(
            name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        )
        val root = Files.createTempDirectory("corpuszipselection").toFile()
        try {
            CorpusZipSelection(downloader).fetch(URI("https://corpus.invalid/archive"), member, root)

            assertContentEquals(bytes, root.resolve("source.wcnf").readBytes())
            assertEquals(listOf("source.wcnf"), root.list()!!.toList())
            assertTrue(transferred < archive.size / 2)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a source hash mismatch leaves the existing published member intact`() {
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("source.wcnf"))
                zip.write("changed".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val downloader = CorpusDownload(open = { uri ->
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
        val root = Files.createTempDirectory("corpuszipselection").toFile()
        root.resolve("source.wcnf").writeText("existing")
        try {
            assertFailsWith<IllegalArgumentException> {
                CorpusZipSelection(downloader).fetch(
                    URI("https://corpus.invalid/archive"), FetchMethod.ZipMember("source.wcnf", "0".repeat(64)), root,
                )
            }

            assertEquals("existing", root.resolve("source.wcnf").readText())
            assertEquals(listOf("source.wcnf"), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing or ambiguous member basenames do not publish a source`(ambiguous: Boolean) {
        val archive = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for (parent in listOf("one", "two")) {
                    zip.putNextEntry(ZipEntry("$parent/other.wcnf"))
                    zip.write(byteArrayOf(1))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val downloader = CorpusDownload(open = { uri ->
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
        val root = Files.createTempDirectory("corpuszipselection").toFile()
        try {
            val name = if (ambiguous) "other.wcnf" else "missing.wcnf"

            assertFailsWith<IllegalArgumentException> {
                CorpusZipSelection(downloader).fetch(
                    URI("https://corpus.invalid/archive"), FetchMethod.ZipMember(name, "0".repeat(64)), root,
                )
            }

            assertEquals(emptyList(), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }
}
