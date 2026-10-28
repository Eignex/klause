package com.eignex.klause.bench.source

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class CorpusDownloadTest {
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
