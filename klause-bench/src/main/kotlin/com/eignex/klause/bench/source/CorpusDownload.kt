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
    fun fetch(uri: URI, destination: File) {
        val parent = destination.absoluteFile.parentFile.toPath()
        Files.createDirectories(parent)
        repeat(ATTEMPTS) { attempt ->
            val partial = Files.createTempFile(parent, ".download-", ".partial")
            var connection: URLConnection? = null
            try {
                connection = open(uri).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                }
                transfer(connection, partial)
                Files.move(partial, destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                return
            } catch (failure: HttpFailure) {
                if (failure.status !in RETRYABLE_STATUS || exhausted(attempt)) throw failure
            } catch (failure: IOException) {
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
            val status = connection.responseCode
            if (status !in 200..299) throw HttpFailure(status)
        }
        val bytes = connection.getInputStream().use { input -> Files.newOutputStream(partial).use { input.copyTo(it) } }
        val expected = connection.contentLengthLong
        if (expected >= 0 && bytes != expected) {
            throw EOFException("corpus download ended after $bytes of $expected bytes")
        }
    }

    internal class HttpFailure(val status: Int) : IOException("corpus download returned HTTP $status")

    private companion object {
        const val ATTEMPTS = 4
        val RETRYABLE_STATUS = setOf(408, 429, 500, 502, 503, 504)
    }
}
