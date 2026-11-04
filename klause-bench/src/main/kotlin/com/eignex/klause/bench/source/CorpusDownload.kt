package com.eignex.klause.bench.source

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class CorpusDownload(
    private val open: (URI) -> URLConnection = { it.toURL().openConnection() },
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    data class RangeIdentity(val size: Long, val etag: String)
    data class Range(val bytes: ByteArray, val identity: RangeIdentity)

    fun range(uri: URI, start: Long, length: Int, identity: RangeIdentity? = null): Range {
        require(start >= 0 && length in 1..65536 && start <= Long.MAX_VALUE - length)
        val end = start + length - 1
        val connection = corpusTransport { open(uri) } as? HttpURLConnection
            ?: error("corpus byte ranges require HTTP")
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("Accept-Encoding", "identity")
            identity?.let { connection.setRequestProperty("If-Match", it.etag) }
            val status = corpusTransport { connection.responseCode }
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                val failure = HttpFailure(status)
                throw if (status in RETRYABLE_STATUS) CorpusUnavailable(failure) else failure
            }
            require(connection.getHeaderField("Content-Encoding").orEmpty() in setOf("", "identity")) {
                "corpus ranges require identity encoding"
            }
            val contentRange = connection.getHeaderField("Content-Range").orEmpty()
            val match = requireNotNull(Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(contentRange)) {
                "invalid corpus Content-Range: $contentRange"
            }
            val (first, last, total) = match.destructured
            require(first.toLong() == start && last.toLong() == end && total.toLong() > end) {
                "corpus range does not match requested bytes"
            }
            val etag = connection.getHeaderField("ETag").orEmpty()
            require(etag.startsWith('"') && etag.endsWith('"') && etag.length > 2) {
                "corpus ranges require a strong ETag"
            }
            val observed = RangeIdentity(total.toLong(), etag)
            require(identity == null || identity == observed) { "corpus archive changed between ranges" }
            require(connection.contentLengthLong == -1L || connection.contentLengthLong == length.toLong()) {
                "corpus range length differs from its headers"
            }
            val bytes = corpusTransport {
                connection.inputStream.use { it.readNBytes(length + 1) }
            }
            require(bytes.size <= length) { "corpus range exceeds requested bytes" }
            if (bytes.size != length) throw CorpusUnavailable(EOFException("truncated corpus range"))
            return Range(bytes, observed)
        } finally {
            runCatching { connection.errorStream?.close() }
            connection.disconnect()
        }
    }

    fun fetch(uri: URI, destination: File) {
        val parent = destination.absoluteFile.parentFile.toPath()
        Files.createDirectories(parent)
        repeat(ATTEMPTS) { attempt ->
            val partial = Files.createTempFile(parent, ".download-", ".partial")
            var connection: URLConnection? = null
            try {
                connection = corpusTransport { open(uri) }.apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                }
                transfer(connection, partial)
                Files.move(partial, destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                return
            } catch (failure: HttpFailure) {
                if (failure.status !in RETRYABLE_STATUS || exhausted(attempt)) {
                    throw if (failure.status in RETRYABLE_STATUS) CorpusUnavailable(failure) else failure
                }
            } catch (failure: CorpusUnavailable) {
                if (exhausted(attempt)) throw failure
            } finally {
                (connection as? HttpURLConnection)?.let { http ->
                    runCatching { http.errorStream?.close() }
                    http.disconnect()
                }
                Files.deleteIfExists(partial)
            }
            pause(250L shl attempt)
        }
    }

    private fun exhausted(attempt: Int): Boolean = Thread.currentThread().isInterrupted || attempt == ATTEMPTS - 1

    private fun transfer(connection: URLConnection, partial: Path) {
        if (connection is HttpURLConnection) {
            val status = corpusTransport { connection.responseCode }
            if (status !in 200..299) throw HttpFailure(status)
        }
        val input = corpusTransport { connection.getInputStream() }
        var bytes = 0L
        try {
            Files.newOutputStream(partial).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = corpusTransport { input.read(buffer) }
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    bytes += count
                }
            }
        } finally {
            corpusTransport { input.close() }
        }
        val expected = connection.contentLengthLong
        if (expected >= 0 && bytes != expected) {
            throw CorpusUnavailable(EOFException("corpus download ended after $bytes of $expected bytes"))
        }
    }

    internal class HttpFailure(val status: Int) : IOException("corpus download returned HTTP $status")

    private companion object {
        const val ATTEMPTS = 4
        val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
    }
}
