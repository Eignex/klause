package com.eignex.klause.bench.source

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CorpusDownloadTest {
    @Test
    fun `a byte range retains the archive identity and requests conditional original bytes`() {
        lateinit var connection: HttpURLConnection
        val downloader = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes 4-6/12"
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getContentLengthLong() = 3L
                override fun getInputStream() = byteArrayOf(4, 5, 6).inputStream()
            }.also { connection = it }
        })
        val identity = CorpusDownload.RangeIdentity(12, "\"archive\"")

        val range = downloader.range(URI("https://corpus.invalid/archive"), 4, 3, identity)

        assertContentEquals(byteArrayOf(4, 5, 6), range.bytes)
        assertEquals(identity, range.identity)
        assertEquals("bytes=4-6", connection.getRequestProperty("Range"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertEquals(identity.etag, connection.getRequestProperty("If-Match"))
    }

    @ParameterizedTest
    @ValueSource(ints = [200, 404, 412])
    fun `a range request never falls back to an unqualified full response`(status: Int) {
        var attempts = 0
        val downloader = CorpusDownload(open = { uri ->
            attempts++
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = status
                override fun getInputStream(): InputStream = error("unqualified response body")
            }
        })

        assertFailsWith<CorpusDownload.HttpFailure> {
            downloader.range(URI("https://corpus.invalid/archive"), 0, 1)
        }

        assertEquals(1, attempts)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "W/\"archive\"", "archive"])
    fun `ranges reject archive identities without a strong ETag`(etag: String) {
        val downloader = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes 0-0/12"
                    "ETag" -> etag
                    else -> null
                }
                override fun getInputStream(): InputStream = error("unqualified response body")
            }
        })

        assertFailsWith<IllegalArgumentException> {
            downloader.range(URI("https://corpus.invalid/archive"), 0, 1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["bytes 1-1/12", "bytes 0-1/12", "bytes 0-0/*", "bytes 0-0/0"])
    fun `ranges reject coordinates that differ from the requested interval`(contentRange: String) {
        val downloader = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> contentRange
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getInputStream(): InputStream = error("unqualified response body")
            }
        })

        assertFailsWith<IllegalArgumentException> {
            downloader.range(URI("https://corpus.invalid/archive"), 0, 1)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a changed archive cannot supply a later range`(sizeChanged: Boolean) {
        val downloader = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> if (sizeChanged) "bytes 0-0/13" else "bytes 0-0/12"
                    "ETag" -> if (sizeChanged) "\"archive\"" else "\"changed\""
                    else -> null
                }
                override fun getInputStream(): InputStream = error("changed response body")
            }
        })

        assertFailsWith<IllegalArgumentException> {
            downloader.range(
                URI("https://corpus.invalid/archive"), 0, 1, CorpusDownload.RangeIdentity(12, "\"archive\""),
            )
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `ranges reject a body with a different byte count`(size: Int) {
        val downloader = CorpusDownload(open = { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 206
                override fun getHeaderField(name: String): String? = when (name) {
                    "Content-Range" -> "bytes 0-0/12"
                    "ETag" -> "\"archive\""
                    else -> null
                }
                override fun getContentLengthLong() = -1L
                override fun getInputStream() = ByteArray(size).inputStream()
            }
        })

        val failure = assertFailsWith<Exception> {
            downloader.range(URI("https://corpus.invalid/archive"), 0, 1)
        }
        if (size == 0) assertIs<CorpusUnavailable>(failure) else assertIs<IllegalArgumentException>(failure)
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `exhausted transient HTTP failures signal infrastructure unavailability`(status: Int) {
        val root = Files.createTempDirectory("corpusdownload").toFile()
        try {
            var attempts = 0
            val downloader = CorpusDownload(open = { uri ->
                attempts++
                object : HttpURLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = status
                }
            }, pause = {})

            assertFailsWith<CorpusUnavailable> {
                downloader.fetch(URI("https://corpus.invalid/archive"), File(root, "archive"))
            }

            assertEquals(4, attempts)
            assertEquals(emptyList(), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `local publication errors are not classified as transient transport failures`() {
        val root = Files.createTempDirectory("corpusdownload").toFile()
        try {
            val destination = File(root, "directory").apply { mkdir() }
            var attempts = 0
            val downloader = CorpusDownload(open = { uri ->
                attempts++
                object : URLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun getInputStream() = "complete".byteInputStream()
                }
            }, pause = {})

            val failure = assertFailsWith<IOException> {
                downloader.fetch(URI("https://corpus.invalid/archive"), destination)
            }

            assertFalse(failure is CorpusUnavailable)
            assertEquals(1, attempts)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an interrupted transfer is retried without publishing or appending its partial bytes`() {
        val root = Files.createTempDirectory("corpusdownload").toFile()
        try {
            val destination = File(root, "archive")
            destination.writeText("existing")
            var attempts = 0
            val downloader = CorpusDownload(open = { uri ->
                attempts++
                assertEquals("existing", destination.readText())
                object : URLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun getInputStream(): InputStream = if (attempts == 1) {
                        object : InputStream() {
                            private var bytes = 0
                            override fun read(): Int {
                                if (bytes++ == 0) return 'x'.code
                                throw IOException("connection dropped")
                            }
                        }
                    } else {
                        "complete".byteInputStream()
                    }
                }
            }, pause = {})

            downloader.fetch(URI("https://corpus.invalid/archive"), destination)

            assertEquals(2, attempts)
            assertEquals("complete", destination.readText())
            assertEquals(listOf("archive"), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `transient HTTP failures are retried`(status: Int) {
        val root = Files.createTempDirectory("corpusdownload").toFile()
        try {
            val destination = File(root, "archive")
            var attempts = 0
            val downloader = CorpusDownload(open = { uri ->
                attempts++
                object : HttpURLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = if (attempts == 1) status else 200
                    override fun getInputStream() = "complete".byteInputStream()
                    override fun getContentLengthLong() = -1L
                }
            }, pause = {})

            downloader.fetch(URI("https://corpus.invalid/archive"), destination)

            assertEquals(2, attempts)
            assertEquals("complete", destination.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 410])
    fun `permanent HTTP failures do not retry or publish a file`(status: Int) {
        val root = Files.createTempDirectory("corpusdownload").toFile()
        try {
            val destination = File(root, "archive")
            var attempts = 0
            val downloader = CorpusDownload(open = { uri ->
                attempts++
                object : HttpURLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = status
                    override fun getContentLengthLong() = -1L
                }
            }, pause = {})

            assertFailsWith<CorpusDownload.HttpFailure> {
                downloader.fetch(URI("https://corpus.invalid/archive"), destination)
            }

            assertEquals(1, attempts)
            assertFalse(destination.exists())
            assertEquals(emptyList(), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `repeated truncated transfers exhaust bounded retries without publishing incomplete data`() {
        val root = Files.createTempDirectory("corpusdownload").toFile()
        try {
            var attempts = 0
            val destination = File(root, "archive")
            val downloader = CorpusDownload(open = { uri ->
                attempts++
                object : URLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun getInputStream() = "short".byteInputStream()
                    override fun getContentLengthLong() = 100L
                }
            }, pause = {})

            assertFailsWith<CorpusUnavailable> {
                downloader.fetch(URI("https://corpus.invalid/archive"), destination)
            }

            assertEquals(4, attempts)
            assertFalse(destination.exists())
            assertEquals(emptyList(), root.list()!!.toList())
        } finally {
            root.deleteRecursively()
        }
    }
}
